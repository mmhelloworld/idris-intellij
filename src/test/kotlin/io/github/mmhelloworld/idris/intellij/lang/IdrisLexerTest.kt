package io.github.mmhelloworld.idris.intellij.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Test

class IdrisLexerTest {

    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = IdrisLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            tokens.add(lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return tokens
    }

    private fun types(text: String): List<IElementType> =
        lex(text).filter { it.first != TokenType.WHITE_SPACE }.map { it.first }

    @Test
    fun `lexes keywords and identifiers`() {
        assertEquals(
            listOf(IdrisTokenTypes.KEYWORD, IdrisTokenTypes.IDENTIFIER, IdrisTokenTypes.OPERATOR,
                IdrisTokenTypes.IDENTIFIER, IdrisTokenTypes.KEYWORD),
            types("data MyList : Type where"),
        )
    }

    @Test
    fun `lexes holes and pragmas`() {
        // `total` is a keyword in its own right, even as a pragma argument
        assertEquals(
            listOf(IdrisTokenTypes.PRAGMA, IdrisTokenTypes.KEYWORD),
            types("%default total"),
        )
        assertEquals(listOf(IdrisTokenTypes.HOLE), types("?todo_rhs"))
    }

    @Test
    fun `total is a keyword but default pragma argument is identifier`() {
        assertEquals(listOf(IdrisTokenTypes.KEYWORD), types("total"))
    }

    @Test
    fun `lexes line and doc comments`() {
        assertEquals(listOf(IdrisTokenTypes.LINE_COMMENT), types("-- a comment"))
        assertEquals(listOf(IdrisTokenTypes.DOC_COMMENT), types("||| Docs here"))
    }

    @Test
    fun `double dash followed by operator char is an operator`() {
        assertEquals(
            listOf(IdrisTokenTypes.IDENTIFIER, IdrisTokenTypes.OPERATOR, IdrisTokenTypes.IDENTIFIER),
            types("a --> b"),
        )
    }

    @Test
    fun `lexes nested block comments as one token`() {
        val tokens = lex("{- outer {- inner -} still outer -}x")
        assertEquals(IdrisTokenTypes.BLOCK_COMMENT, tokens[0].first)
        assertEquals("{- outer {- inner -} still outer -}", tokens[0].second)
        assertEquals(IdrisTokenTypes.IDENTIFIER, tokens[1].first)
    }

    @Test
    fun `lexes multiline strings as one token`() {
        val text = "\"\"\"\nline1\nline2\n\"\"\""
        val tokens = lex(text)
        assertEquals(1, tokens.size)
        assertEquals(IdrisTokenTypes.STRING, tokens[0].first)
    }

    @Test
    fun `lexes strings with escapes`() {
        assertEquals(listOf(IdrisTokenTypes.STRING), types("\"say \\\"hi\\\"\""))
    }

    @Test
    fun `lexes char literals and primed identifiers`() {
        assertEquals(
            listOf(IdrisTokenTypes.IDENTIFIER, IdrisTokenTypes.OPERATOR, IdrisTokenTypes.CHAR),
            types("c = 'x'"),
        )
        assertEquals(listOf(IdrisTokenTypes.IDENTIFIER), types("foo'"))
    }

    @Test
    fun `lexes numbers`() {
        assertEquals(listOf(IdrisTokenTypes.NUMBER), types("42"))
        assertEquals(listOf(IdrisTokenTypes.NUMBER), types("0xFF"))
        assertEquals(listOf(IdrisTokenTypes.NUMBER), types("3.14"))
    }

    @Test
    fun `lexes qualified names as identifier dot identifier`() {
        assertEquals(
            listOf(IdrisTokenTypes.IDENTIFIER, IdrisTokenTypes.OPERATOR, IdrisTokenTypes.IDENTIFIER),
            types("Data.List"),
        )
    }

    @Test
    fun `tokens cover the whole buffer`() {
        val text = "module Main\n\nmain : IO ()\nmain = putStrLn \"hi\" -- end\n"
        val tokens = lex(text)
        assertEquals(text.length, tokens.sumOf { it.second.length })
    }
}
