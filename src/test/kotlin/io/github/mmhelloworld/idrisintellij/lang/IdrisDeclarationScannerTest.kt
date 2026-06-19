package io.github.mmhelloworld.idrisintellij.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdrisDeclarationScannerTest {

    private fun scan(text: String) = IdrisDeclarationScanner.scan(text)

    private fun names(text: String) = scan(text).map { it.name }

    private fun kinds(text: String) = scan(text).map { it.kind }

    @Test
    fun `module header and imports`() {
        val src = """
            module Data.Foo

            import Data.List
            import public Data.Vect
        """.trimIndent()
        assertEquals(listOf("Data.Foo", "Data.List", "Data.Vect"), names(src))
        assertEquals(
            listOf(IdrisDeclKind.MODULE, IdrisDeclKind.IMPORT, IdrisDeclKind.IMPORT),
            kinds(src),
        )
    }

    @Test
    fun `gadt data with indented constructors`() {
        val src = """
            public export
            data Shape : Type where
              Circle    : (radius : Double) -> Shape
              Rectangle : (width : Double) -> (height : Double) -> Shape
        """.trimIndent()
        val decls = scan(src)
        assertEquals(1, decls.size)
        val shape = decls.first()
        assertEquals("Shape", shape.name)
        assertEquals(IdrisDeclKind.DATA, shape.kind)
        assertEquals(listOf("Circle", "Rectangle"), shape.children.map { it.name })
    }

    @Test
    fun `old-style data constructors on the rhs`() {
        val src = "data Bool = True | False"
        val bool = scan(src).single()
        assertEquals("Bool", bool.name)
        assertEquals(listOf("True", "False"), bool.children.map { it.name })
    }

    @Test
    fun `record fields and constructor`() {
        val src = """
            record Person where
              constructor MkPerson
              name : String
              age : Nat
        """.trimIndent()
        val person = scan(src).single()
        assertEquals("Person", person.name)
        assertEquals(IdrisDeclKind.RECORD, person.kind)
        assertEquals(listOf("MkPerson", "name", "age"), person.children.map { it.name })
    }

    @Test
    fun `interface methods`() {
        val src = """
            interface Show a where
              show : a -> String
              showPrec : Prec -> a -> String
        """.trimIndent()
        val iface = scan(src).single()
        assertEquals("Show", iface.name)
        assertEquals(IdrisDeclKind.INTERFACE, iface.kind)
        assertEquals(listOf("show", "showPrec"), iface.children.map { it.name })
    }

    @Test
    fun `type signature merges with its clauses`() {
        val src = """
            area : Shape -> Double
            area (Circle r) = pi * r * r
            area (Rectangle w h) = w * h
        """.trimIndent()
        val decls = scan(src)
        assertEquals(listOf("area"), decls.map { it.name })
        assertEquals(IdrisDeclKind.FUNCTION, decls.single().kind)
    }

    @Test
    fun `where-block locals become children of the function`() {
        val src = """
            sumList : List Nat -> Nat
            sumList xs = go 0 xs
              where
                go : Nat -> List Nat -> Nat
                go acc [] = acc
                go acc (x :: rest) = go (acc + x) rest
        """.trimIndent()
        val fn = scan(src).single()
        assertEquals("sumList", fn.name)
        assertEquals(listOf("go"), fn.children.map { it.name })
        // a caret in the local clause body resolves to the local, not just `total`
        val go = fn.children.single()
        assertTrue(go.range.contains(src.indexOf("acc + x")))
    }

    @Test
    fun `standalone pragma is ignored and does not attach to next decl`() {
        val src = """
            %default total

            public export
            area : Shape -> Double
        """.trimIndent()
        assertEquals(listOf("area"), names(src))
    }

    @Test
    fun `operator definition is named`() {
        val src = "(<+>) : a -> a -> a"
        assertEquals("(<+>)", scan(src).single().name)
    }

    @Test
    fun `name range points at the declared name`() {
        val src = "module Foo\n\narea : Int"
        val area = scan(src).first { it.name == "area" }
        assertEquals("area", src.substring(area.nameRange.startOffset, area.nameRange.endOffset))
    }

    @Test
    fun `member range covers its whole line not just the name`() {
        val src = """
            data Shape : Type where
              Circle : (radius : Double) -> Shape
        """.trimIndent()
        val circle = scan(src).single().children.single()
        assertEquals("Circle", circle.name)
        // nameRange points at just `Circle`; range spans the whole constructor line,
        // so a caret anywhere on it (e.g. on `Double`) resolves to the member.
        assertTrue(circle.range.length > circle.nameRange.length)
        assertTrue(circle.range.contains(src.indexOf("Double")))
    }

    @Test
    fun `decl range covers leading modifiers`() {
        val src = "public export\ndata Shape : Type where\n  Circle : Shape"
        val shape = scan(src).single()
        assertTrue("range should start at the modifier line", shape.range.startOffset == 0)
    }
}
