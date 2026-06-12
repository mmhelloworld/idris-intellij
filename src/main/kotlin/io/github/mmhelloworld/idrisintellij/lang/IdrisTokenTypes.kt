package io.github.mmhelloworld.idrisintellij.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class IdrisTokenType(debugName: String) : IElementType(debugName, IdrisLanguage)

object IdrisTokenTypes {
    @JvmField val LINE_COMMENT = IdrisTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = IdrisTokenType("BLOCK_COMMENT")
    @JvmField val DOC_COMMENT = IdrisTokenType("DOC_COMMENT")
    @JvmField val KEYWORD = IdrisTokenType("KEYWORD")
    @JvmField val IDENTIFIER = IdrisTokenType("IDENTIFIER")
    @JvmField val HOLE = IdrisTokenType("HOLE")
    @JvmField val PRAGMA = IdrisTokenType("PRAGMA")
    @JvmField val OPERATOR = IdrisTokenType("OPERATOR")
    @JvmField val STRING = IdrisTokenType("STRING")
    @JvmField val CHAR = IdrisTokenType("CHAR")
    @JvmField val NUMBER = IdrisTokenType("NUMBER")
    @JvmField val LPAREN = IdrisTokenType("LPAREN")
    @JvmField val RPAREN = IdrisTokenType("RPAREN")
    @JvmField val LBRACKET = IdrisTokenType("LBRACKET")
    @JvmField val RBRACKET = IdrisTokenType("RBRACKET")
    @JvmField val LBRACE = IdrisTokenType("LBRACE")
    @JvmField val RBRACE = IdrisTokenType("RBRACE")
    @JvmField val COMMA = IdrisTokenType("COMMA")
    @JvmField val SEMICOLON = IdrisTokenType("SEMICOLON")
    @JvmField val BACKTICK = IdrisTokenType("BACKTICK")

    @JvmField val FILE = IFileElementType(IdrisLanguage)

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING, CHAR)

    /** Idris 2 keywords — src/Parser/Lexer/Source.idr `keywords` + `fixityKeywords`. */
    val KEYWORDS: Set<String> = setOf(
        "data", "module", "where", "let", "in", "do", "record",
        "auto", "default", "implicit", "failing", "mutual", "namespace",
        "parameters", "with", "proof", "impossible", "case", "of",
        "if", "then", "else", "forall", "rewrite", "typebind", "autobind",
        "using", "interface", "implementation", "open", "import",
        "public", "export", "private",
        "infixl", "infixr", "infix", "prefix",
        "total", "partial", "covering",
    )
}
