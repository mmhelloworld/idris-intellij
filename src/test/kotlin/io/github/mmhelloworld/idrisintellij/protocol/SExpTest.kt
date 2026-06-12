package io.github.mmhelloworld.idrisintellij.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SExpTest {

    @Test
    fun `renders strings escaping only backslash and quote`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"".replace("\\n", "\n"),
            SExp.SString("a\"b\\c\nd").render())
    }

    @Test
    fun `renders booleans as colon-capitalized symbols`() {
        assertEquals(":True", SExp.SBool(true).render())
        assertEquals(":False", SExp.SBool(false).render())
    }

    @Test
    fun `renders nested lists`() {
        val sexp = SExp.SList(
            SExp.SSymbol("load-file"),
            SExp.SString("Main.idr"),
        )
        assertEquals("(:load-file \"Main.idr\")", sexp.render())
    }

    @Test
    fun `parses what it renders`() {
        val original = SExp.SList(
            SExp.SSymbol("return"),
            SExp.SList(SExp.SSymbol("ok"), SExp.SString("plus : Nat -> Nat -> Nat")),
            SExp.SInt(4),
        )
        assertEquals(original, SExpParser.parse(original.render()))
    }

    @Test
    fun `parses True and False symbols as booleans`() {
        assertEquals(SExp.SBool(true), SExpParser.parse(":True"))
        assertEquals(SExp.SBool(false), SExpParser.parse(":False"))
    }

    @Test
    fun `parses negative integers`() {
        assertEquals(SExp.SInt(-42), SExpParser.parse("-42"))
    }

    @Test
    fun `parses escaped quotes inside strings`() {
        val parsed = SExpParser.parse("(\"say \\\"hi\\\" \\\\ done\")")
        val items = parsed.asList!!
        assertEquals("say \"hi\" \\ done", items[0].asString)
    }

    @Test
    fun `parses a realistic protocol message`() {
        val parsed = SExpParser.parse("(:protocol-version 2 1)")
        val items = parsed.asList!!
        assertEquals("protocol-version", items[0].asSymbol)
        assertEquals(2L, items[1].asLong)
        assertTrue(items.size == 3)
    }
}
