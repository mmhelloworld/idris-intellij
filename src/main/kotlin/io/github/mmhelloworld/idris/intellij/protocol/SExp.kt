package io.github.mmhelloworld.idris.intellij.protocol

/**
 * S-expressions as used by the Idris 2 IDE-mode protocol (Protocol/SExp.idr).
 *
 * Rendering rules (must match the compiler exactly):
 *  - strings escape only `\` and `"`
 *  - booleans render as `:True` / `:False`
 *  - symbols render with a leading colon
 */
sealed class SExp {

    data class SList(val items: List<SExp>) : SExp() {
        constructor(vararg items: SExp) : this(items.toList())
    }

    data class SString(val value: String) : SExp()

    data class SSymbol(val name: String) : SExp()

    data class SInt(val value: Long) : SExp()

    data class SBool(val value: Boolean) : SExp()

    fun render(): String = when (this) {
        is SList -> items.joinToString(" ", "(", ")") { it.render() }
        is SString -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is SSymbol -> ":$name"
        is SInt -> value.toString()
        is SBool -> if (value) ":True" else ":False"
    }

    companion object {
        fun list(vararg items: SExp): SExp = SList(items.toList())
        fun sym(name: String): SExp = SSymbol(name)
        fun str(value: String): SExp = SString(value)
        fun int(value: Long): SExp = SInt(value)
        fun int(value: Int): SExp = SInt(value.toLong())
    }
}

/** Convenience accessors used by the reply decoders. */
val SExp.asList: List<SExp>? get() = (this as? SExp.SList)?.items
val SExp.asString: String? get() = (this as? SExp.SString)?.value
val SExp.asSymbol: String?
    get() = when (this) {
        is SExp.SSymbol -> name
        is SExp.SBool -> if (value) "True" else "False"
        else -> null
    }
val SExp.asLong: Long? get() = (this as? SExp.SInt)?.value

class SExpParseException(message: String) : Exception(message)

/**
 * Recursive-descent parser for the S-expression dialect produced by the compiler.
 * Lenient where the wire format allows it (bare atoms parse as symbols).
 */
object SExpParser {

    fun parse(text: String): SExp {
        val state = State(text)
        state.skipWhitespace()
        val result = state.parseExpr()
        state.skipWhitespace()
        return result
    }

    private class State(val text: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseExpr(): SExp {
            if (pos >= text.length) throw SExpParseException("Unexpected end of input")
            return when (val c = text[pos]) {
                '(' -> parseList()
                '"' -> parseString()
                ':' -> parseSymbol()
                else ->
                    if (c == '-' || c.isDigit()) parseNumberOrAtom()
                    else parseBareAtom()
            }
        }

        private fun parseList(): SExp {
            pos++ // '('
            val items = mutableListOf<SExp>()
            while (true) {
                skipWhitespace()
                if (pos >= text.length) throw SExpParseException("Unterminated list")
                if (text[pos] == ')') {
                    pos++
                    return SExp.SList(items)
                }
                items.add(parseExpr())
            }
        }

        private fun parseString(): SExp {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < text.length) {
                when (val c = text[pos]) {
                    '\\' -> {
                        if (pos + 1 >= text.length) throw SExpParseException("Dangling escape")
                        sb.append(text[pos + 1])
                        pos += 2
                    }
                    '"' -> {
                        pos++
                        return SExp.SString(sb.toString())
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
            throw SExpParseException("Unterminated string")
        }

        private fun parseSymbol(): SExp {
            pos++ // ':'
            val name = consumeAtomText()
            return when (name) {
                "True" -> SExp.SBool(true)
                "False" -> SExp.SBool(false)
                else -> SExp.SSymbol(name)
            }
        }

        private fun parseNumberOrAtom(): SExp {
            val start = pos
            val atom = consumeAtomText()
            val asLong = atom.toLongOrNull()
            if (asLong != null) return SExp.SInt(asLong)
            pos = start
            return parseBareAtom()
        }

        private fun parseBareAtom(): SExp {
            val name = consumeAtomText()
            if (name.isEmpty()) throw SExpParseException("Unexpected character '${text[pos]}' at $pos")
            return SExp.SSymbol(name)
        }

        private fun consumeAtomText(): String {
            val start = pos
            while (pos < text.length && !text[pos].isWhitespace() && text[pos] != '(' && text[pos] != ')' && text[pos] != '"') {
                pos++
            }
            return text.substring(start, pos)
        }
    }
}
