package io.github.mmhelloworld.idris.intellij.protocol

import io.github.mmhelloworld.idris.intellij.protocol.SExp.Companion.int
import io.github.mmhelloworld.idris.intellij.protocol.SExp.Companion.list
import io.github.mmhelloworld.idris.intellij.protocol.SExp.Companion.str
import io.github.mmhelloworld.idris.intellij.protocol.SExp.Companion.sym

/**
 * Command constructors for the v1 feature set (Protocol/IDE/Command.idr).
 *
 * Lines sent TO the server are 1-based (Idris/REPL.idr `processEdit` subtracts 1).
 * `:type-of`'s column is 0-based; the editing commands use the 2-argument form
 * (no column) which targets the whole line.
 */
object IdeCommands {

    fun loadFile(relativePath: String): SExp = list(sym("load-file"), str(relativePath))

    fun interpret(expression: String): SExp = list(sym("interpret"), str(expression))

    fun typeOf(name: String): SExp = list(sym("type-of"), str(name))

    fun typeOfAt(name: String, line1: Int, col0: Int): SExp =
        list(sym("type-of"), str(name), int(line1), int(col0))

    fun docsFor(name: String): SExp = list(sym("docs-for"), str(name), sym("full"))

    fun caseSplit(line1: Int, name: String): SExp = list(sym("case-split"), int(line1), str(name))

    fun addClause(line1: Int, name: String): SExp = list(sym("add-clause"), int(line1), str(name))

    fun proofSearch(line1: Int, name: String): SExp =
        list(sym("proof-search"), int(line1), str(name))

    fun generateDef(line1: Int, name: String): SExp = list(sym("generate-def"), int(line1), str(name))

    fun makeLemma(line1: Int, name: String): SExp = list(sym("make-lemma"), int(line1), str(name))

    fun makeCase(line1: Int, name: String): SExp = list(sym("make-case"), int(line1), str(name))

    fun makeWith(line1: Int, name: String): SExp = list(sym("make-with"), int(line1), str(name))

    fun nameAt(qualifiedName: String): SExp = list(sym("name-at"), str(qualifiedName))

    fun version(): SExp = list(sym("version"))
}

/** Result of `(:name-at ...)`: definition locations with ABSOLUTE file paths, 0-based. */
data class NameLocation(val name: String, val span: FileSpan)

/** Result of `(:make-lemma ...)`. */
data class MetaVarLemma(val application: String, val lemmaSignature: String)

object ResultDecoder {

    /**
     * Observed wire shape (idris2 0.8.x): the file-context props are inlined
     * after the name — `(("Defs.triple" (:filename "/abs") (:start l c) (:end l c)) ...)`.
     * The nested shape `(("name" ((:filename ...) ...)))` is kept as a fallback.
     */
    fun parseNameLocations(payload: SExp): List<NameLocation> =
        payload.asList.orEmpty().mapNotNull { entry ->
            val parts = entry.asList ?: return@mapNotNull null
            val name = parts.getOrNull(0)?.asString ?: return@mapNotNull null
            val span = ReplyDecoder.decodeFileContext(SExp.SList(parts.drop(1)))
                ?: parts.getOrNull(1)?.let { ReplyDecoder.decodeFileContext(it) }
                ?: return@mapNotNull null
            NameLocation(name, span)
        }

    /** `(:metavariable-lemma (:replace-metavariable "app") (:definition-type "sig"))` */
    fun parseMetaVarLemma(payload: SExp): MetaVarLemma? {
        val items = payload.asList ?: return null
        if (items.firstOrNull()?.asSymbol != "metavariable-lemma") return null
        var application: String? = null
        var signature: String? = null
        for (item in items.drop(1)) {
            val kv = item.asList ?: continue
            when (kv.firstOrNull()?.asSymbol) {
                "replace-metavariable" -> application = kv.getOrNull(1)?.asString
                "definition-type" -> signature = kv.getOrNull(1)?.asString
            }
        }
        return MetaVarLemma(application ?: return null, signature ?: return null)
    }
}
