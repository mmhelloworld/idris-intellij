package io.github.mmhelloworld.idrisintellij.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdrisDocumentationProviderTest {

    // Real `:docs-for "length"` output captured from idris2 0.8 (four same-named
    // definitions). Built with explicit newlines because the String.length block
    // contains an INDENTED blank line ("  ") and fenced examples — exactly the
    // detail lines that must stay attached to their header.
    private val docs = listOf(
        "Data.List1.length : List1 a -> Nat",
        "  Totality: total",
        "  Visibility: public export",
        "Prelude.List.length : List a -> Nat",
        "  Returns the length of the list.",
        "  Totality: total",
        "  Visibility: public export",
        "Prelude.SnocList.length : SnocList a -> Nat",
        "  Totality: total",
        "  Visibility: public export",
        "Prelude.String.length : String -> Nat",
        "  Returns the length of the string.",
        "  ",
        "  ```idris example",
        "  length \"\"",
        "  ```",
        "  Totality: total",
        "  Visibility: public export",
    ).joinToString("\n")

    @Test
    fun `keeps only the block for the resolved name`() {
        val block = IdrisDocumentationProvider.selectDocBlock(docs, "Prelude.List.length")
        assertEquals(
            "Prelude.List.length : List a -> Nat\n" +
                "  Returns the length of the list.\n" +
                "  Totality: total\n" +
                "  Visibility: public export",
            block,
        )
    }

    @Test
    fun `preserves indented blank lines and fenced examples inside a block`() {
        val block = IdrisDocumentationProvider.selectDocBlock(docs, "Prelude.String.length")!!
        assertEquals(
            "Prelude.String.length : String -> Nat\n" +
                "  Returns the length of the string.\n" +
                "  \n" +
                "  ```idris example\n" +
                "  length \"\"\n" +
                "  ```\n" +
                "  Totality: total\n" +
                "  Visibility: public export",
            block,
        )
    }

    @Test
    fun `returns null when no block matches the resolved name`() {
        assertNull(IdrisDocumentationProvider.selectDocBlock(docs, "Data.Vect.length"))
    }

    @Test
    fun `spaces out type, documentation, totality and visibility sections`() {
        val block = IdrisDocumentationProvider.selectDocBlock(docs, "Prelude.List.length")!!
        assertEquals(
            "Prelude.List.length : List a -> Nat\n" +
                "\n" +
                "  Returns the length of the list.\n" +
                "\n" +
                "  Totality: total\n" +
                "\n" +
                "  Visibility: public export",
            IdrisDocumentationProvider.spaceSections(block),
        )
    }

    @Test
    fun `undocumented block separates type from metadata without a double blank`() {
        val block = IdrisDocumentationProvider.selectDocBlock(docs, "Data.List1.length")!!
        assertEquals(
            "Data.List1.length : List1 a -> Nat\n" +
                "\n" +
                "  Totality: total\n" +
                "\n" +
                "  Visibility: public export",
            IdrisDocumentationProvider.spaceSections(block),
        )
    }

    @Test
    fun `single-line type fallback is left unchanged`() {
        assertEquals(
            "Prelude.List.length : List a -> Nat",
            IdrisDocumentationProvider.spaceSections("Prelude.List.length : List a -> Nat"),
        )
    }
}
