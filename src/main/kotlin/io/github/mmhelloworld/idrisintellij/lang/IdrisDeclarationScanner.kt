package io.github.mmhelloworld.idrisintellij.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

enum class IdrisDeclKind {
    MODULE, IMPORT, DATA, RECORD, INTERFACE, IMPLEMENTATION, NAMESPACE, FUNCTION, TYPE_SIG, OTHER
}

/**
 * One declaration found by [IdrisDeclarationScanner]. [nameRange] points at the
 * declared name (for navigation/breadcrumbs); [range] covers the whole block
 * including any leading modifiers and indented body (for folding).
 */
data class IdrisDecl(
    val name: String,
    val kind: IdrisDeclKind,
    val nameRange: TextRange,
    val range: TextRange,
    val children: List<IdrisDecl> = emptyList(),
)

/**
 * A layout-based outline of an Idris source file, derived purely from the lexer
 * (the PSI tree is flat). A top-level declaration begins at the first non-trivia
 * token of a column-0 line, after skipping leading visibility/totality modifier
 * keywords; standalone `%`pragma lines are ignored. Constructors / record fields
 * / interface methods on the indented body lines become children.
 *
 * Best-effort and heuristic: it is not a parser. `where`-local definitions are
 * not descended into, and constrained interface / complex implementation heads
 * are named approximately.
 */
object IdrisDeclarationScanner {

    private val MODIFIERS = setOf("public", "export", "private", "total", "partial", "covering")

    private data class Tok(val type: IElementType, val start: Int, val end: Int, val text: String)

    private class Line(val start: Int, val firstCol: Int, val tokens: List<Tok>)

    /** A column-0 line that opens a declaration, plus its indented body lines. */
    private class Block(val prefixStart: Int, val header: Line, val body: MutableList<Line> = mutableListOf())

    fun scan(text: CharSequence): List<IdrisDecl> {
        val lines = toLines(text)
        val blocks = toBlocks(lines)
        val decls = blocks.mapNotNull { toDecl(it) }
        return mergeClauses(decls)
    }

    // --- tokenisation & line grouping -------------------------------------

    private fun isTrivia(type: IElementType): Boolean =
        type == TokenType.WHITE_SPACE ||
            type == IdrisTokenTypes.LINE_COMMENT ||
            type == IdrisTokenTypes.BLOCK_COMMENT ||
            type == IdrisTokenTypes.DOC_COMMENT

    private fun toLines(text: CharSequence): List<Line> {
        val lexer = IdrisLexer()
        lexer.start(text, 0, text.length, 0)
        val lineStarts = ArrayList<Int>().apply { add(0) }
        for (i in text.indices) if (text[i] == '\n') lineStarts.add(i + 1)

        fun lineOf(offset: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }

        val byLine = HashMap<Int, MutableList<Tok>>()
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (!isTrivia(type)) {
                val tok = Tok(type, lexer.tokenStart, lexer.tokenEnd, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString())
                byLine.getOrPut(lineOf(tok.start)) { mutableListOf() }.add(tok)
            }
            lexer.advance()
        }
        return byLine.entries.sortedBy { it.key }.map { (line, toks) ->
            Line(lineStarts[line], toks.first().start - lineStarts[line], toks)
        }
    }

    // --- block grouping ---------------------------------------------------

    private fun isModifierOnly(line: Line): Boolean =
        line.tokens.all { it.type == IdrisTokenTypes.KEYWORD && it.text in MODIFIERS }

    private fun isPragma(line: Line): Boolean = line.tokens.first().type == IdrisTokenTypes.PRAGMA

    private fun toBlocks(lines: List<Line>): List<Block> {
        val blocks = ArrayList<Block>()
        var current: Block? = null
        var modifierStart: Int? = null
        for (line in lines) {
            when {
                line.firstCol > 0 -> current?.body?.add(line)
                isPragma(line) -> modifierStart = null
                isModifierOnly(line) -> if (modifierStart == null) modifierStart = line.start
                else -> { // column-0 declaration header
                    current = Block(modifierStart ?: line.start, line)
                    modifierStart = null
                    blocks.add(current)
                }
            }
        }
        return blocks
    }

    // --- declaration extraction -------------------------------------------

    private fun blockRange(block: Block): TextRange {
        val lastTok = (block.body.lastOrNull() ?: block.header).tokens.last()
        return TextRange(block.prefixStart, lastTok.end)
    }

    private fun toDecl(block: Block): IdrisDecl? {
        val sig = block.header.tokens.dropWhile { it.type == IdrisTokenTypes.KEYWORD && it.text in MODIFIERS }
        val first = sig.firstOrNull() ?: return null
        val range = blockRange(block)

        fun decl(kind: IdrisDeclKind, name: String, nameRange: TextRange, children: List<IdrisDecl> = emptyList()) =
            IdrisDecl(name, kind, nameRange, range, children)

        if (first.type == IdrisTokenTypes.KEYWORD) {
            return when (first.text) {
                "module" -> dottedName(sig, 1)?.let { decl(IdrisDeclKind.MODULE, it.first, it.second) }
                "import" -> dottedName(sig, 1)?.let { decl(IdrisDeclKind.IMPORT, it.first, it.second) }
                "data" -> identName(sig, 1)?.let { decl(IdrisDeclKind.DATA, it.first, it.second, dataChildren(block, sig)) }
                "record" -> identName(sig, 1)?.let { decl(IdrisDeclKind.RECORD, it.first, it.second, recordChildren(block)) }
                "interface" -> identName(sig, 1)?.let { decl(IdrisDeclKind.INTERFACE, it.first, it.second, methodChildren(block)) }
                "implementation" -> implementationName(sig)?.let { decl(IdrisDeclKind.IMPLEMENTATION, it.first, it.second) }
                "namespace" -> dottedName(sig, 1)?.let { decl(IdrisDeclKind.NAMESPACE, it.first, it.second) }
                else -> null // mutual / parameters / failing / using ... — skip as a container
            }
        }

        // A function clause or type signature, or an operator definition `(<>) ...`.
        val whereKids = whereChildren(block)
        val opName = operatorName(sig)
        if (opName != null) return decl(functionKind(sig), opName.first, opName.second, whereKids)
        if (first.type == IdrisTokenTypes.IDENTIFIER) {
            return decl(functionKind(sig), first.text, TextRange(first.start, first.end), whereKids)
        }
        return null
    }

    /** Local definitions of a `where` block become children of the function. */
    private fun whereChildren(block: Block): List<IdrisDecl> {
        val whereTok = (block.header.tokens.asSequence() + block.body.asSequence().flatMap { it.tokens })
            .firstOrNull { it.type == IdrisTokenTypes.KEYWORD && it.text == "where" } ?: return emptyList()
        val region = block.body.filter { it.tokens.first().start > whereTok.end }
        if (region.isEmpty()) return emptyList()

        // The members sit at the block's least indentation; deeper lines are their bodies.
        val baseCol = region.minOf { it.firstCol }
        val members = ArrayList<Pair<Line, MutableList<Line>>>()
        for (line in region) {
            if (line.firstCol == baseCol) members.add(line to mutableListOf()) else members.lastOrNull()?.second?.add(line)
        }
        return mergeClauses(members.mapNotNull { (header, body) -> functionDecl(header, body) })
    }

    /** Builds a function/type-signature declaration from a header line and its continuation lines. */
    private fun functionDecl(header: Line, body: List<Line>): IdrisDecl? {
        val sig = header.tokens.dropWhile { it.type == IdrisTokenTypes.KEYWORD && it.text in MODIFIERS }
        val first = sig.firstOrNull() ?: return null
        val lastTok = (body.lastOrNull() ?: header).tokens.last()
        val range = TextRange(header.tokens.first().start, lastTok.end)
        operatorName(sig)?.let { return IdrisDecl(it.first, functionKind(sig), it.second, range) }
        if (first.type == IdrisTokenTypes.IDENTIFIER) {
            return IdrisDecl(first.text, functionKind(sig), TextRange(first.start, first.end), range)
        }
        return null
    }

    /** TYPE_SIG when a top-level `:` precedes any `=`, else FUNCTION. */
    private fun functionKind(sig: List<Tok>): IdrisDeclKind {
        for (tok in sig) {
            if (tok.type == IdrisTokenTypes.OPERATOR && tok.text == ":") return IdrisDeclKind.TYPE_SIG
            if (tok.type == IdrisTokenTypes.OPERATOR && tok.text == "=") return IdrisDeclKind.FUNCTION
        }
        return IdrisDeclKind.FUNCTION
    }

    /** `(<op>)` at the head of a definition → name `(<op>)`. */
    private fun operatorName(sig: List<Tok>): Pair<String, TextRange>? {
        if (sig.size < 3) return null
        if (sig[0].type != IdrisTokenTypes.LPAREN || sig[1].type != IdrisTokenTypes.OPERATOR || sig[2].type != IdrisTokenTypes.RPAREN) {
            return null
        }
        return "(${sig[1].text})" to TextRange(sig[0].start, sig[2].end)
    }

    /**
     * Joins a qualified name starting at the first identifier at/after [from]
     * (skipping e.g. `public` in `import public Data.Vect`).
     */
    private fun dottedName(sig: List<Tok>, from: Int): Pair<String, TextRange>? {
        var i = (from until sig.size).firstOrNull { sig[it].type == IdrisTokenTypes.IDENTIFIER } ?: return null
        val sb = StringBuilder()
        var start = -1
        var end = -1
        while (i < sig.size) {
            val t = sig[i]
            val isDot = t.type == IdrisTokenTypes.OPERATOR && t.text == "."
            if (t.type != IdrisTokenTypes.IDENTIFIER && !isDot) break
            if (start < 0) start = t.start
            end = t.end
            sb.append(t.text)
            i++
        }
        return if (start < 0) null else sb.toString() to TextRange(start, end)
    }

    private fun identName(sig: List<Tok>, from: Int): Pair<String, TextRange>? =
        sig.drop(from).firstOrNull { it.type == IdrisTokenTypes.IDENTIFIER }
            ?.let { it.text to TextRange(it.start, it.end) }

    /** `implementation [Constraints =>] Head ... [where]` — name = the head up to `where`. */
    private fun implementationName(sig: List<Tok>): Pair<String, TextRange>? {
        val parts = sig.drop(1).takeWhile { !(it.type == IdrisTokenTypes.KEYWORD && it.text == "where") }
        if (parts.isEmpty()) return null
        val name = parts.joinToString(" ") { it.text }
        return name to TextRange(parts.first().start, parts.last().end)
    }

    // --- children ---------------------------------------------------------

    /** [range] covers the member's whole extent (its line/segment) so a caret anywhere on it resolves to the member. */
    private fun child(nameTok: Tok, range: TextRange, kind: IdrisDeclKind) =
        IdrisDecl(nameTok.text, kind, TextRange(nameTok.start, nameTok.end), range)

    private fun lineRange(line: Line): TextRange = TextRange(line.tokens.first().start, line.tokens.last().end)

    private fun dataChildren(block: Block, sig: List<Tok>): List<IdrisDecl> {
        // Old-style `data X = A | B args | C`: constructors live on the header RHS.
        val eq = sig.indexOfFirst { it.type == IdrisTokenTypes.OPERATOR && it.text == "=" }
        if (eq >= 0) {
            return sig.drop(eq + 1)
                .splitBy { it.type == IdrisTokenTypes.OPERATOR && it.text == "|" }
                .mapNotNull { seg ->
                    val nameTok = seg.firstOrNull { it.type == IdrisTokenTypes.IDENTIFIER } ?: return@mapNotNull null
                    child(nameTok, TextRange(seg.first().start, seg.last().end), IdrisDeclKind.DATA)
                }
        }
        // GADT `data X : Type where` — each indented `Ctor : ...` line.
        return block.body.mapNotNull { line ->
            if (line.tokens.none { it.type == IdrisTokenTypes.OPERATOR && it.text == ":" }) return@mapNotNull null
            line.tokens.firstOrNull { it.type == IdrisTokenTypes.IDENTIFIER }
                ?.let { child(it, lineRange(line), IdrisDeclKind.DATA) }
        }
    }

    private fun recordChildren(block: Block): List<IdrisDecl> = block.body.flatMap { line ->
        val first = line.tokens.first()
        if (first.text == "constructor") { // `constructor` is lexed as an identifier, not a keyword
            line.tokens.getOrNull(1)?.takeIf { it.type == IdrisTokenTypes.IDENTIFIER }
                ?.let { listOf(child(it, lineRange(line), IdrisDeclKind.FUNCTION)) }
                ?: emptyList()
        } else {
            fieldNames(line)
        }
    }

    private fun methodChildren(block: Block): List<IdrisDecl> = block.body.mapNotNull { line ->
        if (line.tokens.none { it.type == IdrisTokenTypes.OPERATOR && it.text == ":" }) return@mapNotNull null
        line.tokens.firstOrNull { it.type == IdrisTokenTypes.IDENTIFIER }
            ?.let { child(it, lineRange(line), IdrisDeclKind.FUNCTION) }
    }

    /** `x, y : T` → fields x, y (each owning the whole line). */
    private fun fieldNames(line: Line): List<IdrisDecl> {
        val colon = line.tokens.indexOfFirst { it.type == IdrisTokenTypes.OPERATOR && it.text == ":" }
        if (colon < 0) return emptyList()
        return line.tokens.take(colon)
            .filter { it.type == IdrisTokenTypes.IDENTIFIER }
            .map { child(it, lineRange(line), IdrisDeclKind.FUNCTION) }
    }

    // --- post-processing --------------------------------------------------

    /**
     * Collapses a type signature and its following same-name clauses into one
     * entry, combining any `where`-members the clauses contribute.
     */
    private fun mergeClauses(decls: List<IdrisDecl>): List<IdrisDecl> {
        val merged = ArrayList<IdrisDecl>()
        for (decl in decls) {
            val prev = merged.lastOrNull()
            if (prev != null && prev.name == decl.name &&
                prev.kind.isFunctionLike() && decl.kind.isFunctionLike()
            ) {
                merged[merged.size - 1] = prev.copy(
                    kind = IdrisDeclKind.FUNCTION,
                    range = prev.range.union(decl.range),
                    children = prev.children + decl.children,
                )
            } else {
                merged.add(decl)
            }
        }
        return merged
    }

    private fun IdrisDeclKind.isFunctionLike() = this == IdrisDeclKind.FUNCTION || this == IdrisDeclKind.TYPE_SIG

    private inline fun <T> List<T>.splitBy(sep: (T) -> Boolean): List<List<T>> {
        val out = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        for (item in this) {
            if (sep(item)) {
                out.add(cur); cur = ArrayList()
            } else {
                cur.add(item)
            }
        }
        out.add(cur)
        return out
    }
}
