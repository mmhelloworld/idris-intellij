package io.github.mmhelloworld.idrisintellij.protocol

import java.io.EOFException
import java.io.Reader

/**
 * Wire framing for the IDE-mode protocol (Idris/IDEMode/Commands.idr `send`,
 * Idris/IDEMode/REPL.idr `getInput`):
 *
 *  - every message is `XXXXXX<payload>` where XXXXXX is the payload length as
 *    6 lowercase hex digits, left-padded with '0'
 *  - the length counts CHARACTERS (Unicode codepoints), not bytes — Idris
 *    `String` length is codepoint-based
 *
 * Both directions count the s-expression plus the trailing `"\n"` (`send` in
 * Commands.idr) — [FrameReader] expects that on the read side.
 *
 * Compatibility note: idris2-jvm builds up to 0.8.1 cannot run multi-command
 * stdio sessions with this (spec-conformant) framing — their `fEOF`
 * (ByteBufferIo.isEof) performed a blocking read when its buffer was empty,
 * withholding the reply to command N until command N+1's bytes arrived. Fixed
 * in idris2-jvm 0.8.2 (C `feof` flag semantics). Scheme-built compilers were
 * never affected.
 */
object IdeModeFraming {

    fun encode(sexp: SExp): String {
        val payload = sexp.render() + "\n"
        val length = payload.codePointCount(0, payload.length)
        return String.format("%06x", length) + payload
    }

    /** Wraps a command with its request id: `(<command> <id>)`. */
    fun encodeRequest(command: SExp, requestId: Long): String =
        encode(SExp.SList(listOf(command, SExp.SInt(requestId))))
}

/**
 * Reads framed messages. Counts codepoints (a surrogate pair is one unit) and
 * is defensive about off-by-codepoint discrepancies: if the read payload has
 * unbalanced parentheses it keeps reading until the terminating newline.
 */
class FrameReader(private val reader: Reader) {

    /** Returns the next message payload (without the length header), or null at EOF. */
    fun readFrame(): String? {
        val header = CharArray(6)
        var read = 0
        while (read < 6) {
            val n = reader.read(header, read, 6 - read)
            if (n < 0) {
                if (read == 0) return null
                throw EOFException("EOF inside frame header")
            }
            read += n
        }
        val length = String(header).trim().toIntOrNull(16)
            ?: throw SExpParseException("Invalid frame header: '${String(header)}'")

        val sb = StringBuilder()
        var codePoints = 0
        while (codePoints < length) {
            val c = reader.read()
            if (c < 0) throw EOFException("EOF inside frame payload")
            sb.append(c.toChar())
            if (Character.isHighSurrogate(c.toChar())) {
                val low = reader.read()
                if (low < 0) throw EOFException("EOF inside frame payload")
                sb.append(low.toChar())
            }
            codePoints++
        }
        // Defensive: absorb any counting discrepancy by completing to the newline.
        if (!isBalanced(sb)) {
            while (true) {
                val c = reader.read()
                if (c < 0) break
                sb.append(c.toChar())
                if (c.toChar() == '\n') break
            }
        }
        return sb.toString()
    }

    private fun isBalanced(text: CharSequence): Boolean {
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++
                inString && c == '"' -> inString = false
                !inString && c == '"' -> inString = true
                !inString && c == '(' -> depth++
                !inString && c == ')' -> depth--
            }
            i++
        }
        return depth <= 0 && !inString
    }
}
