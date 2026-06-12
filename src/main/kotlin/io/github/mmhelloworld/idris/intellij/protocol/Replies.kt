package io.github.mmhelloworld.idris.intellij.protocol

/**
 * Decoded server messages (Protocol/IDE.idr `SExpable Reply`).
 *
 * All positions coming FROM the server are 0-based line/column with an
 * exclusive end column (raw internal compiler values, see
 * Idris/IDEMode/Commands.idr `Cast (FileName, NonEmptyFC) FileContext`).
 */
sealed class Reply {

    data class ProtocolVersion(val major: Long, val minor: Long) : Reply()

    /** `(:return (:ok ...)|(:error "msg") id)` — the final reply to a request. */
    data class Return(val ok: Boolean, val payload: SExp, val errorMessage: String?, val id: Long) : Reply()

    /** `(:output (:ok (:highlight-source (...))) id)` — async semantic highlight chunk. */
    data class Highlights(val spans: List<SemanticSpan>, val id: Long) : Reply()

    data class WriteString(val text: String, val id: Long) : Reply()

    data class SetPrompt(val text: String, val id: Long) : Reply()

    /** `(:warning ("file" (sl sc) (el ec) "msg" ...) id)` — diagnostics, including compile ERRORS. */
    data class Warning(val diagnostic: IdrisDiagnostic, val id: Long) : Reply()

    data class Unknown(val sexp: SExp) : Reply()
}

/** 0-based, end-exclusive positions; `file` is as sent by the compiler (usually root-relative). */
data class IdrisDiagnostic(
    val file: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val message: String,
)

/**
 * One semantic-highlight span (Protocol/IDE/Highlight.idr). `name`/`namespace`
 * are present only for the Full form; the location is the OCCURRENCE of the
 * name, not its definition.
 */
data class SemanticSpan(
    val file: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val decor: String,
    val name: String? = null,
    val namespace: String? = null,
) {
    val qualifiedName: String?
        get() = name?.let { if (namespace.isNullOrEmpty()) it else "$namespace.$it" }
}

data class FileSpan(
    val file: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)

object ReplyDecoder {

    fun decode(sexp: SExp): Reply {
        val items = sexp.asList ?: return Reply.Unknown(sexp)
        val head = items.firstOrNull()?.asSymbol ?: return Reply.Unknown(sexp)
        return when (head) {
            "protocol-version" -> decodeProtocolVersion(items, sexp)
            "return" -> decodeReturn(items, sexp)
            "output" -> decodeOutput(items, sexp)
            "write-string" -> decodeWithStringArg(items, sexp) { s, id -> Reply.WriteString(s, id) }
            "set-prompt" -> decodeWithStringArg(items, sexp) { s, id -> Reply.SetPrompt(s, id) }
            "warning" -> decodeWarning(items, sexp)
            else -> Reply.Unknown(sexp)
        }
    }

    private fun decodeProtocolVersion(items: List<SExp>, sexp: SExp): Reply {
        val major = items.getOrNull(1)?.asLong ?: return Reply.Unknown(sexp)
        val minor = items.getOrNull(2)?.asLong ?: 0
        return Reply.ProtocolVersion(major, minor)
    }

    private fun decodeReturn(items: List<SExp>, sexp: SExp): Reply {
        val id = items.getOrNull(2)?.asLong ?: return Reply.Unknown(sexp)
        val body = items.getOrNull(1)?.asList ?: return Reply.Unknown(sexp)
        return when (body.firstOrNull()?.asSymbol) {
            "ok" -> Reply.Return(true, body.getOrNull(1) ?: SExp.SList(emptyList()), null, id)
            "error" -> Reply.Return(false, body.getOrNull(1) ?: SExp.SList(emptyList()),
                body.getOrNull(1)?.asString ?: "Unknown error", id)
            else -> Reply.Unknown(sexp)
        }
    }

    private fun decodeOutput(items: List<SExp>, sexp: SExp): Reply {
        val id = items.getOrNull(2)?.asLong ?: return Reply.Unknown(sexp)
        val body = items.getOrNull(1)?.asList ?: return Reply.Unknown(sexp)
        if (body.firstOrNull()?.asSymbol != "ok") return Reply.Unknown(sexp)
        val highlightSource = body.getOrNull(1)?.asList ?: return Reply.Unknown(sexp)
        if (highlightSource.firstOrNull()?.asSymbol != "highlight-source") return Reply.Unknown(sexp)
        val spans = highlightSource.getOrNull(1)?.asList.orEmpty().mapNotNull(::decodeHighlight)
        return Reply.Highlights(spans, id)
    }

    private inline fun decodeWithStringArg(items: List<SExp>, sexp: SExp, make: (String, Long) -> Reply): Reply {
        val text = items.getOrNull(1)?.asString ?: return Reply.Unknown(sexp)
        val id = items.getOrNull(2)?.asLong ?: return Reply.Unknown(sexp)
        return make(text, id)
    }

    private fun decodeWarning(items: List<SExp>, sexp: SExp): Reply {
        val id = items.getOrNull(2)?.asLong ?: return Reply.Unknown(sexp)
        val body = items.getOrNull(1)?.asList ?: return Reply.Unknown(sexp)
        val file = body.getOrNull(0)?.asString ?: return Reply.Unknown(sexp)
        val start = body.getOrNull(1)?.asList ?: return Reply.Unknown(sexp)
        val end = body.getOrNull(2)?.asList ?: return Reply.Unknown(sexp)
        val message = body.getOrNull(3)?.asString ?: return Reply.Unknown(sexp)
        return Reply.Warning(
            IdrisDiagnostic(
                file = file,
                startLine = (start.getOrNull(0)?.asLong ?: 0).toInt(),
                startCol = (start.getOrNull(1)?.asLong ?: 0).toInt(),
                endLine = (end.getOrNull(0)?.asLong ?: 0).toInt(),
                endCol = (end.getOrNull(1)?.asLong ?: 0).toInt(),
                message = message,
            ),
            id,
        )
    }

    /**
     * Highlight entry: `(<fileContext> (<props>))` where props is either the Full
     * form `((:name "n") (:namespace "ns") (:decor :function) ...)` or the
     * lightweight form `((:decor :keyword))`.
     */
    fun decodeHighlight(sexp: SExp): SemanticSpan? {
        val pair = sexp.asList ?: return null
        val location = decodeFileContext(pair.getOrNull(0) ?: return null) ?: return null
        val props = pair.getOrNull(1)?.asList ?: return null
        var name: String? = null
        var namespace: String? = null
        var decor: String? = null
        for (prop in props) {
            val kv = prop.asList ?: continue
            when (kv.firstOrNull()?.asSymbol) {
                "name" -> name = kv.getOrNull(1)?.asString
                "namespace" -> namespace = kv.getOrNull(1)?.asString
                "decor" -> decor = kv.getOrNull(1)?.asSymbol
            }
        }
        return SemanticSpan(
            file = location.file,
            startLine = location.startLine,
            startCol = location.startCol,
            endLine = location.endLine,
            endCol = location.endCol,
            decor = decor ?: return null,
            name = name,
            namespace = namespace,
        )
    }

    /** `((:filename "f") (:start l c) (:end l c))` — Protocol/IDE/FileContext.idr. */
    fun decodeFileContext(sexp: SExp): FileSpan? {
        val props = sexp.asList ?: return null
        var file: String? = null
        var startLine = 0
        var startCol = 0
        var endLine = 0
        var endCol = 0
        for (prop in props) {
            val kv = prop.asList ?: continue
            when (kv.firstOrNull()?.asSymbol) {
                "filename" -> file = kv.getOrNull(1)?.asString
                "start" -> {
                    startLine = (kv.getOrNull(1)?.asLong ?: 0).toInt()
                    startCol = (kv.getOrNull(2)?.asLong ?: 0).toInt()
                }
                "end" -> {
                    endLine = (kv.getOrNull(1)?.asLong ?: 0).toInt()
                    endCol = (kv.getOrNull(2)?.asLong ?: 0).toInt()
                }
            }
        }
        return FileSpan(file ?: return null, startLine, startCol, endLine, endCol)
    }
}
