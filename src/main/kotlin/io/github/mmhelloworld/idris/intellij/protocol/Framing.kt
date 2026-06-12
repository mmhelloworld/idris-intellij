package io.github.mmhelloworld.idris.intellij.protocol

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
 * JVM-runtime workaround (verified empirically against idris2-jvm 0.8.x): the
 * server loop calls `fEOF` between reading a request and replying
 * (Idris/IDEMode/REPL.idr `loop`), and the JVM runtime's `fEOF`
 * (ByteBufferIo.isEof) performs a BLOCKING read when its buffer is empty. A
 * spec-framed request leaves the buffer exactly empty, so the reply to command
 * N is held hostage until command N+1's bytes arrive. We therefore append a
 * sacrificial sync line ([SYNC_TRAILER]) after every frame: it keeps the
 * buffer non-empty (so `fEOF` returns immediately and the reply flushes), and
 * the server then consumes it as one unparseable line, emitting a
 * `(:return (:error "Parse error..."))` for an already-completed request id,
 * which clients ignore. The trailer is harmless on Scheme-built compilers.
 */
object IdeModeFraming {

    /**
     * Six non-hex characters and a newline: `getNChars 6` consumes the junk,
     * the hex parse fails, and `getFLine` consumes the newline, leaving the
     * stream aligned for the next real frame. Must contain NO hex digits so
     * that [FrameReader]'s between-frames skip can pass over it entirely.
     */
    const val SYNC_TRAILER = "??!!??\n"

    fun encode(sexp: SExp): String {
        val payload = sexp.render() + "\n"
        val length = payload.codePointCount(0, payload.length)
        return String.format("%06x", length) + payload + SYNC_TRAILER
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
        // Skip anything between frames that cannot start a length header
        // (e.g. the client-side SYNC_TRAILER when tests read client frames).
        var first: Int
        do {
            first = reader.read()
            if (first < 0) return null
        } while (Character.digit(first.toChar(), 16) < 0)
        header[0] = first.toChar()
        var read = 1
        while (read < 6) {
            val n = reader.read(header, read, 6 - read)
            if (n < 0) throw EOFException("EOF inside frame header")
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
