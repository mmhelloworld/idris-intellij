package io.github.mmhelloworld.idrisintellij.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyDecoderTest {

    private fun decode(text: String): Reply = ReplyDecoder.decode(SExpParser.parse(text))

    @Test
    fun `decodes protocol version`() {
        val reply = decode("(:protocol-version 2 1)") as Reply.ProtocolVersion
        assertEquals(2L, reply.major)
        assertEquals(1L, reply.minor)
    }

    @Test
    fun `decodes ok return with string payload`() {
        val reply = decode("(:return (:ok \"plus : Nat -> Nat -> Nat\") 4)") as Reply.Return
        assertTrue(reply.ok)
        assertEquals(4L, reply.id)
        assertEquals("plus : Nat -> Nat -> Nat", reply.payload.asString)
    }

    @Test
    fun `decodes ok return with trailing span metadata`() {
        val reply = decode("(:return (:ok \"Nat\" ((0 3) ((:decor :type)))) 2)") as Reply.Return
        assertTrue(reply.ok)
        assertEquals("Nat", reply.payload.asString)
    }

    @Test
    fun `decodes error return`() {
        val reply = decode("(:return (:error \"Couldn't find Main\") 7)") as Reply.Return
        assertFalse(reply.ok)
        assertEquals("Couldn't find Main", reply.errorMessage)
    }

    @Test
    fun `decodes warning with zero-based positions`() {
        val reply = decode(
            "(:warning (\"Main.idr\" (4 7) (4 12) \"Error: Undefined name foo.\" ()) 1)"
        ) as Reply.Warning
        assertEquals("Main.idr", reply.diagnostic.file)
        assertEquals(4, reply.diagnostic.startLine)
        assertEquals(7, reply.diagnostic.startCol)
        assertEquals(12, reply.diagnostic.endCol)
        assertEquals("Error: Undefined name foo.", reply.diagnostic.message)
    }

    @Test
    fun `decodes full highlight spans`() {
        val text = "(:output (:ok (:highlight-source ((((:filename \"Main.idr\") " +
            "(:start 2 0) (:end 2 4)) ((:name \"main\") (:namespace \"Main\") (:decor :function) " +
            "(:implicit :False) (:key \"\") (:doc-overview \"\") (:type \"\")))))) 1)"
        val reply = decode(text) as Reply.Highlights
        assertEquals(1, reply.spans.size)
        val span = reply.spans[0]
        assertEquals("Main.idr", span.file)
        assertEquals(2, span.startLine)
        assertEquals(0, span.startCol)
        assertEquals(4, span.endCol)
        assertEquals("function", span.decor)
        assertEquals("main", span.name)
        assertEquals("Main.main", span.qualifiedName)
    }

    @Test
    fun `decodes lightweight highlight spans`() {
        val text = "(:output (:ok (:highlight-source ((((:filename \"Main.idr\") " +
            "(:start 0 0) (:end 0 6)) ((:decor :keyword)))))) 1)"
        val reply = decode(text) as Reply.Highlights
        assertEquals("keyword", reply.spans[0].decor)
        assertEquals(null, reply.spans[0].name)
    }

    @Test
    fun `decodes write-string`() {
        val reply = decode("(:write-string \"hello world\" 9)") as Reply.WriteString
        assertEquals("hello world", reply.text)
        assertEquals(9L, reply.id)
    }

    @Test
    fun `parses name-at results in the observed flat shape`() {
        // Exact shape captured from idris2 0.8.x on the wire
        val payload = SExpParser.parse(
            "((\"Defs.triple\" (:filename \"/private/tmp/Defs.idr\") (:start 2 0) (:end 2 19)))"
        )
        val locations = ResultDecoder.parseNameLocations(payload)
        assertEquals(1, locations.size)
        assertEquals("Defs.triple", locations[0].name)
        assertEquals("/private/tmp/Defs.idr", locations[0].span.file)
        assertEquals(2, locations[0].span.startLine)
        assertEquals(19, locations[0].span.endCol)
    }

    @Test
    fun `parses name-at results in the nested shape`() {
        val payload = SExpParser.parse(
            "((\"Main.main\" ((:filename \"/abs/Main.idr\") (:start 2 0) (:end 2 4))))"
        )
        val locations = ResultDecoder.parseNameLocations(payload)
        assertEquals(1, locations.size)
        assertEquals("/abs/Main.idr", locations[0].span.file)
    }

    @Test
    fun `parses holes with premises`() {
        // Exact shape captured from idris2 0.8.x: hole name double-encoded,
        // premise names padded with quantity markers
        val payload = SExpParser.parse(
            "((\"\\\"Holey.mkPair_rhs\\\"\" ((\" 0  a\" \"Type\" ()) (\"  x\" \"a\" ())) (\"(a, b)\" ())))"
        )
        val holes = ResultDecoder.parseHoles(payload)
        assertEquals(1, holes.size)
        assertEquals("Holey.mkPair_rhs", holes[0].name)
        assertEquals("(a, b)", holes[0].type)
        assertEquals(2, holes[0].premises.size)
        assertEquals("0  a", holes[0].premises[0].name)
        assertEquals("x", holes[0].premises[1].name)
        assertEquals("a", holes[0].premises[1].type)
    }

    @Test
    fun `parses completions`() {
        val payload = SExpParser.parse("((\"length\" \"lengthSuffix\") \"\")")
        assertEquals(listOf("length", "lengthSuffix"), ResultDecoder.parseCompletions(payload))
    }

    @Test
    fun `parses intro candidates from list or string`() {
        assertEquals(
            listOf("(?a, ?b)"),
            ResultDecoder.parseIntroCandidates(SExpParser.parse("(\"(?a, ?b)\")")),
        )
        assertEquals(
            listOf("Nil", "(::) ?x ?xs"),
            ResultDecoder.parseIntroCandidates(SExpParser.parse("(\"Nil\" \"(::) ?x ?xs\")")),
        )
    }

    @Test
    fun `parses metavariable lemma`() {
        val payload = SExpParser.parse(
            "(:metavariable-lemma (:replace-metavariable \"lemma_rhs xs\") (:definition-type \"lemma_rhs : List a -> Nat\"))"
        )
        val lemma = ResultDecoder.parseMetaVarLemma(payload)!!
        assertEquals("lemma_rhs xs", lemma.application)
        assertEquals("lemma_rhs : List a -> Nat", lemma.lemmaSignature)
    }
}
