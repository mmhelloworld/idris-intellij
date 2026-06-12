package io.github.mmhelloworld.idrisintellij.lang

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class IdrisColorSettingsPage : ColorSettingsPage {

    private val descriptors = arrayOf(
        AttributesDescriptor("Keyword", IdrisColors.KEYWORD),
        AttributesDescriptor("Identifier", IdrisColors.IDENTIFIER),
        AttributesDescriptor("Hole", IdrisColors.HOLE),
        AttributesDescriptor("Pragma", IdrisColors.PRAGMA),
        AttributesDescriptor("Operator", IdrisColors.OPERATOR),
        AttributesDescriptor("String", IdrisColors.STRING),
        AttributesDescriptor("Number", IdrisColors.NUMBER),
        AttributesDescriptor("Line comment", IdrisColors.LINE_COMMENT),
        AttributesDescriptor("Block comment", IdrisColors.BLOCK_COMMENT),
        AttributesDescriptor("Doc comment", IdrisColors.DOC_COMMENT),
        AttributesDescriptor("Parentheses", IdrisColors.PARENTHESES),
        AttributesDescriptor("Brackets", IdrisColors.BRACKETS),
        AttributesDescriptor("Braces", IdrisColors.BRACES),
        AttributesDescriptor("Semantic//Type", IdrisColors.SEM_TYPE),
        AttributesDescriptor("Semantic//Function", IdrisColors.SEM_FUNCTION),
        AttributesDescriptor("Semantic//Data constructor", IdrisColors.SEM_DATA),
        AttributesDescriptor("Semantic//Keyword", IdrisColors.SEM_KEYWORD),
        AttributesDescriptor("Semantic//Bound variable", IdrisColors.SEM_BOUND),
        AttributesDescriptor("Semantic//Namespace", IdrisColors.SEM_NAMESPACE),
        AttributesDescriptor("Semantic//Postulate", IdrisColors.SEM_POSTULATE),
        AttributesDescriptor("Semantic//Module", IdrisColors.SEM_MODULE),
    )

    override fun getIcon(): Icon = IdrisIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = IdrisSyntaxHighlighter()

    override fun getDemoText(): String = """
        ||| Compute the length of a list.
        module Data.MyList

        %default total

        import Data.Nat

        {- A simple
           {- nested -} block comment -}

        data MyList : Type -> Type where
          Nil  : MyList a
          (::) : a -> MyList a -> MyList a

        length : MyList a -> Nat
        length Nil = 0
        length (x :: xs) = 1 + length xs

        greeting : String
        greeting = "Hello, " ++ "Idris " ++ show 2.0

        todo : MyList Char -> Nat
        todo xs = ?todo_rhs
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Idris"
}
