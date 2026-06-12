package io.github.mmhelloworld.idrisintellij.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SingleLazyInstanceSyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

open class IdrisSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = IdrisLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        val key = when (tokenType) {
            IdrisTokenTypes.KEYWORD -> IdrisColors.KEYWORD
            IdrisTokenTypes.IDENTIFIER -> IdrisColors.IDENTIFIER
            IdrisTokenTypes.HOLE -> IdrisColors.HOLE
            IdrisTokenTypes.PRAGMA -> IdrisColors.PRAGMA
            IdrisTokenTypes.OPERATOR, IdrisTokenTypes.BACKTICK -> IdrisColors.OPERATOR
            IdrisTokenTypes.STRING, IdrisTokenTypes.CHAR -> IdrisColors.STRING
            IdrisTokenTypes.NUMBER -> IdrisColors.NUMBER
            IdrisTokenTypes.LINE_COMMENT -> IdrisColors.LINE_COMMENT
            IdrisTokenTypes.BLOCK_COMMENT -> IdrisColors.BLOCK_COMMENT
            IdrisTokenTypes.DOC_COMMENT -> IdrisColors.DOC_COMMENT
            IdrisTokenTypes.LPAREN, IdrisTokenTypes.RPAREN -> IdrisColors.PARENTHESES
            IdrisTokenTypes.LBRACKET, IdrisTokenTypes.RBRACKET -> IdrisColors.BRACKETS
            IdrisTokenTypes.LBRACE, IdrisTokenTypes.RBRACE -> IdrisColors.BRACES
            IdrisTokenTypes.COMMA, IdrisTokenTypes.SEMICOLON -> IdrisColors.COMMA
            else -> null
        }
        return if (key == null) emptyArray() else arrayOf(key)
    }
}

class IdrisSyntaxHighlighterFactory : SingleLazyInstanceSyntaxHighlighterFactory() {
    override fun createHighlighter(): SyntaxHighlighter = IdrisSyntaxHighlighter()
}
