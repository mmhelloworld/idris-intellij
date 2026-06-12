package io.github.mmhelloworld.idrisintellij.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class FramingTest {

    @Test
    fun `length is lowercase hex padded to six and counts the trailing newline`() {
        // ((:version) 1)\n = 15 chars — spec framing, requires idris2-jvm >= 0.8.3
        // (older JVM builds had a blocking fEOF that stalled spec-framed sessions)
        val encoded = IdeModeFraming.encodeRequest(SExp.SList(SExp.SSymbol("version")), 1)
        assertEquals("00000f((:version) 1)\n", encoded)
    }

    @Test
    fun `length counts codepoints not UTF-16 units`() {
        // U+1F600 is one codepoint but two UTF-16 chars
        val emoji = String(Character.toChars(0x1F600))
        val sexp = SExp.SString(emoji)
        val payload = sexp.render() + "\n"
        val encoded = IdeModeFraming.encode(sexp)
        val expectedLength = payload.codePointCount(0, payload.length)
        assertEquals(String.format("%06x", expectedLength), encoded.substring(0, 6))
        assertEquals(4, expectedLength) // quote + emoji + quote + newline
    }

    @Test
    fun `frame reader round-trips with the encoder`() {
        val message = SExp.SList(SExp.SSymbol("write-string"), SExp.SString("hello"), SExp.SInt(3))
        val reader = FrameReader(StringReader(IdeModeFraming.encode(message)))
        assertEquals(message, SExpParser.parse(reader.readFrame()!!))
    }

    @Test
    fun `frame reader handles surrogate pairs in payloads`() {
        val emoji = String(Character.toChars(0x1F600))
        val message = SExp.SList(SExp.SSymbol("write-string"), SExp.SString("a${emoji}b"), SExp.SInt(1))
        val reader = FrameReader(StringReader(IdeModeFraming.encode(message)))
        assertEquals(message, SExpParser.parse(reader.readFrame()!!))
    }

    @Test
    fun `frame reader reads consecutive frames`() {
        val first = SExp.SList(SExp.SSymbol("protocol-version"), SExp.SInt(2), SExp.SInt(1))
        val second = SExp.SList(SExp.SSymbol("return"), SExp.SList(SExp.SSymbol("ok"), SExp.SList(emptyList())), SExp.SInt(1))
        val reader = FrameReader(StringReader(IdeModeFraming.encode(first) + IdeModeFraming.encode(second)))
        assertEquals(first, SExpParser.parse(reader.readFrame()!!))
        assertEquals(second, SExpParser.parse(reader.readFrame()!!))
        assertNull(reader.readFrame())
    }
}
