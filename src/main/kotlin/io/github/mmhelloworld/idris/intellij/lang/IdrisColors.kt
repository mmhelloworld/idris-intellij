package io.github.mmhelloworld.idris.intellij.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey

object IdrisColors {
    // Lexical layer
    @JvmField val KEYWORD = createTextAttributesKey("IDRIS_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val IDENTIFIER = createTextAttributesKey("IDRIS_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
    @JvmField val HOLE = createTextAttributesKey("IDRIS_HOLE", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    @JvmField val PRAGMA = createTextAttributesKey("IDRIS_PRAGMA", DefaultLanguageHighlighterColors.METADATA)
    @JvmField val OPERATOR = createTextAttributesKey("IDRIS_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    @JvmField val STRING = createTextAttributesKey("IDRIS_STRING", DefaultLanguageHighlighterColors.STRING)
    @JvmField val NUMBER = createTextAttributesKey("IDRIS_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    @JvmField val LINE_COMMENT = createTextAttributesKey("IDRIS_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = createTextAttributesKey("IDRIS_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    @JvmField val DOC_COMMENT = createTextAttributesKey("IDRIS_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
    @JvmField val PARENTHESES = createTextAttributesKey("IDRIS_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    @JvmField val BRACKETS = createTextAttributesKey("IDRIS_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    @JvmField val BRACES = createTextAttributesKey("IDRIS_BRACES", DefaultLanguageHighlighterColors.BRACES)
    @JvmField val COMMA = createTextAttributesKey("IDRIS_COMMA", DefaultLanguageHighlighterColors.COMMA)

    // Semantic layer (compiler decorations from :load-file)
    @JvmField val SEM_TYPE = createTextAttributesKey("IDRIS_SEM_TYPE", DefaultLanguageHighlighterColors.CLASS_NAME)
    @JvmField val SEM_FUNCTION = createTextAttributesKey("IDRIS_SEM_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    @JvmField val SEM_DATA = createTextAttributesKey("IDRIS_SEM_DATA", DefaultLanguageHighlighterColors.CONSTANT)
    @JvmField val SEM_KEYWORD = createTextAttributesKey("IDRIS_SEM_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val SEM_BOUND = createTextAttributesKey("IDRIS_SEM_BOUND", DefaultLanguageHighlighterColors.PARAMETER)
    @JvmField val SEM_NAMESPACE = createTextAttributesKey("IDRIS_SEM_NAMESPACE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
    @JvmField val SEM_POSTULATE = createTextAttributesKey("IDRIS_SEM_POSTULATE", DefaultLanguageHighlighterColors.STATIC_FIELD)
    @JvmField val SEM_MODULE = createTextAttributesKey("IDRIS_SEM_MODULE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)

    /** Maps a compiler decoration symbol (`:function` etc.) to attributes; null = skip. */
    fun forDecoration(decor: String): TextAttributesKey? = when (decor) {
        "type" -> SEM_TYPE
        "function" -> SEM_FUNCTION
        "data" -> SEM_DATA
        "keyword" -> SEM_KEYWORD
        "bound" -> SEM_BOUND
        "namespace" -> SEM_NAMESPACE
        "postulate" -> SEM_POSTULATE
        "module" -> SEM_MODULE
        else -> null // :comment — the lexer already covers comments
    }
}
