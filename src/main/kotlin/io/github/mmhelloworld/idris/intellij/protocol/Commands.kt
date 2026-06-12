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

    /** Holes in the loaded file; the argument is the pretty-print width. */
    fun metavariables(width: Int = 120): SExp = list(sym("metavariables"), int(width))

    fun replCompletions(prefix: String): SExp = list(sym("repl-completions"), str(prefix))

    fun intro(line1: Int, holeName: String): SExp = list(sym("intro"), int(line1), str(holeName))

    fun refine(line1: Int, holeName: String, expression: String): SExp =
        list(sym("refine"), int(line1), str(holeName), str(expression))

    /** Bare-symbol commands: cycle to the next search result. */
    fun proofSearchNext(): SExp = sym("proof-search-next")

    fun generateDefNext(): SExp = sym("generate-def-next")
}

/** Result of `(:name-at ...)`: definition locations with ABSOLUTE file paths, 0-based. */
data class NameLocation(val name: String, val span: FileSpan)

/** Result of `(:make-lemma ...)`. */
data class MetaVarLemma(val application: String, val lemmaSignature: String)

/** One premise of a hole's context (Protocol/IDE/Holes.idr HolePremise). */
data class HolePremise(val name: String, val type: String)

/** One hole from `(:metavariables N)` (Protocol/IDE/Holes.idr HoleData). */
data class Hole(val name: String, val type: String, val premises: List<HolePremise>)

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

    /**
     * `(("Ns.hole" ((pname ptype ()) ...) ("type" ())) ...)` — each entry is
     * name, premise list, and a (conclusion, metadata) pair. Wire quirks
     * (verified against idris2 0.8.x): the hole name arrives double-encoded
     * (`show` on a String adds literal quotes), and premise names carry
     * quantity/alignment padding like `" 0  b"` or `"  x"`.
     */
    fun parseHoles(payload: SExp): List<Hole> =
        payload.asList.orEmpty().mapNotNull { entry ->
            val parts = entry.asList ?: return@mapNotNull null
            val name = parts.getOrNull(0)?.asString?.removeSurrounding("\"") ?: return@mapNotNull null
            val premises = parts.getOrNull(1)?.asList.orEmpty().mapNotNull { premise ->
                val fields = premise.asList ?: return@mapNotNull null
                HolePremise(
                    fields.getOrNull(0)?.asString?.trim() ?: return@mapNotNull null,
                    fields.getOrNull(1)?.asString ?: return@mapNotNull null,
                )
            }
            val type = parts.getOrNull(2)?.asList?.getOrNull(0)?.asString ?: return@mapNotNull null
            Hole(name, type, premises)
        }

    /** `(("completion1" "completion2" ...) "context")` */
    fun parseCompletions(payload: SExp): List<String> =
        payload.asList?.getOrNull(0)?.asList.orEmpty().mapNotNull { it.asString }

    /**
     * `(:intro ...)` results: a list of candidate replacements (one per
     * constructor that fits); tolerate a plain string for single results.
     */
    fun parseIntroCandidates(payload: SExp): List<String> =
        payload.asString?.let { listOf(it) }
            ?: payload.asList.orEmpty().mapNotNull { it.asString }

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
