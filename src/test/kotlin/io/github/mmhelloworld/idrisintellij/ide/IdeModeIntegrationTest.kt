package io.github.mmhelloworld.idrisintellij.ide

import io.github.mmhelloworld.idrisintellij.protocol.AsyncReplyListener
import io.github.mmhelloworld.idrisintellij.protocol.IdeCommands
import io.github.mmhelloworld.idrisintellij.protocol.IdeModeConnection
import io.github.mmhelloworld.idrisintellij.protocol.IdrisDiagnostic
import io.github.mmhelloworld.idrisintellij.protocol.SemanticSpan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Integration tests against a REAL idris2 compiler in ide-mode. Enabled by
 * setting IDRIS2_EXEC, e.g. for the JVM backend:
 *
 *   IDRIS2_EXEC=$HOME/project/idris-jvm/build/exec/idris2 ./gradlew test
 *
 * These tests are the executable assertions for the protocol facts the plugin
 * relies on: the greeting, 0-based output positions with exclusive end column,
 * and the per-command response shapes.
 */
class IdeModeIntegrationTest {

    private lateinit var workDir: File
    private var process: Process? = null
    private var connection: IdeModeConnection? = null

    private val executable: String? =
        System.getenv("IDRIS2_EXEC")?.takeIf { it.isNotBlank() && File(it).canExecute() }

    @Before
    fun setUp() {
        assumeTrue("IDRIS2_EXEC not set; skipping integration tests", executable != null)
        workDir = File.createTempFile("idris-it", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        connection?.close()
        process?.destroyForcibly()
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    private fun connect(): IdeModeConnection {
        val proc = ProcessBuilder(executable, "--ide-mode")
            .directory(workDir)
            .start()
        process = proc
        val conn = IdeModeConnection(
            InputStreamReader(proc.inputStream, StandardCharsets.UTF_8),
            OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8),
            "idris2-it",
        )
        connection = conn
        val greeting = conn.greeting.get(120, TimeUnit.SECONDS)
        assertEquals("ide-mode protocol major version", 2L, greeting.major)
        assertEquals(
            "runtime stdio health probe",
            io.github.mmhelloworld.idrisintellij.protocol.RuntimeProbe.HEALTHY,
            conn.probeRuntime(),
        )
        return conn
    }

    private fun writeSource(name: String, content: String): File =
        File(workDir, name).apply { writeText(content) }

    private fun load(
        conn: IdeModeConnection,
        fileName: String,
    ): Triple<Boolean, List<IdrisDiagnostic>, List<SemanticSpan>> {
        val diagnostics = CopyOnWriteArrayList<IdrisDiagnostic>()
        val highlights = CopyOnWriteArrayList<SemanticSpan>()
        val listener = object : AsyncReplyListener {
            override fun onWarning(diagnostic: IdrisDiagnostic) {
                diagnostics.add(diagnostic)
            }

            override fun onHighlights(spans: List<SemanticSpan>) {
                highlights.addAll(spans)
            }
        }
        val result = conn.request(IdeCommands.loadFile(fileName), 300_000, listener)
            .get(300, TimeUnit.SECONDS)
        return Triple(result.ok, diagnostics.toList(), highlights.toList())
    }

    @Test
    fun `load error reports zero-based positions with exclusive end column`() {
        val conn = connect()
        // "bogus" occupies line index 3 (0-based), columns 8..13 (0-based, end-exclusive)
        writeSource(
            "Broken.idr",
            """
            module Broken

            answer : Nat
            answer = bogus
            """.trimIndent() + "\n",
        )
        val (ok, diagnostics, _) = load(conn, "Broken.idr")
        assertFalse("load should fail", ok)
        assertTrue("expected at least one diagnostic", diagnostics.isNotEmpty())
        val diagnostic = diagnostics.first { it.message.contains("bogus") }
        assertEquals(3, diagnostic.startLine)
        assertEquals(9, diagnostic.startCol)
        assertEquals(14, diagnostic.endCol)
    }

    @Test
    fun `clean load emits semantic highlights and type-of answers`() {
        val conn = connect()
        writeSource(
            "Clean.idr",
            """
            module Clean

            double : Nat -> Nat
            double n = n + n
            """.trimIndent() + "\n",
        )
        val (ok, _, highlights) = load(conn, "Clean.idr")
        assertTrue("load should succeed", ok)
        assertTrue("expected semantic highlights", highlights.isNotEmpty())
        assertTrue(
            "expected a :function decoration for 'double'",
            highlights.any { it.decor == "function" && it.name == "double" },
        )

        val typeOf = conn.request(IdeCommands.typeOf("double"), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue(typeOf.ok)
        assertTrue(typeOf.text!!.contains("Nat -> Nat"))
    }

    @Test
    fun `case split and add clause return replacement text`() {
        val conn = connect()
        writeSource(
            "Split.idr",
            """
            module Split

            length' : List a -> Nat
            length' xs = ?length_rhs
            """.trimIndent() + "\n",
        )
        val (ok, _, _) = load(conn, "Split.idr")
        assertTrue(ok)

        // case-split on `xs` (clause is the 4th line => 1-based line 4)
        val split = conn.request(IdeCommands.caseSplit(4, "xs"), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("case-split should succeed: ${split.errorMessage}", split.ok)
        val clauses = split.text!!
        assertTrue("expected nil clause: $clauses", clauses.contains("[]"))
        assertTrue("expected cons clause: $clauses", clauses.contains("::"))

        // proof-search the hole on line 4 of the ORIGINAL file (still loaded state)
        val search = conn.request(IdeCommands.proofSearch(4, "length_rhs"), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("proof-search should succeed: ${search.errorMessage}", search.ok)
    }

    @Test
    fun `metavariables, completions and intro answer against a real compiler`() {
        val conn = connect()
        writeSource(
            "Holey.idr",
            """
            module Holey

            mkPair : a -> b -> (a, b)
            mkPair x y = ?mkPair_rhs
            """.trimIndent() + "\n",
        )
        val (ok, _, _) = load(conn, "Holey.idr")
        assertTrue(ok)

        val holesResult = conn.request(IdeCommands.metavariables(), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("metavariables should succeed: ${holesResult.errorMessage}", holesResult.ok)
        val holes = io.github.mmhelloworld.idrisintellij.protocol.ResultDecoder.parseHoles(holesResult.payload)
        assertEquals(1, holes.size)
        assertTrue(holes[0].name.endsWith("mkPair_rhs"))
        assertTrue("premises should include x and y", holes[0].premises.map { it.name }.containsAll(listOf("x", "y")))

        val completions = conn.request(IdeCommands.replCompletions("mkP"), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("repl-completions should succeed: ${completions.errorMessage}", completions.ok)
        val names = io.github.mmhelloworld.idrisintellij.protocol.ResultDecoder.parseCompletions(completions.payload)
        assertTrue("expected mkPair in $names", names.any { it.startsWith("mkPair") })

        // intro on the hole (line 4, 1-based)
        val intro = conn.request(IdeCommands.intro(4, "mkPair_rhs"), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("intro should succeed: ${intro.errorMessage}", intro.ok)
        val candidates = io.github.mmhelloworld.idrisintellij.protocol.ResultDecoder.parseIntroCandidates(intro.payload)
        assertTrue("expected a pair constructor candidate: $candidates",
            candidates.isNotEmpty() && candidates[0].contains(","))
    }

    @Test
    fun `docs-for returns the type signature and the doc comment for a bare name`() {
        val conn = connect()
        writeSource(
            "Docs.idr",
            """
            module Docs

            ||| Doubles a natural number.
            double : Nat -> Nat
            double n = n + n
            """.trimIndent() + "\n",
        )
        val (ok, _, _) = load(conn, "Docs.idr")
        assertTrue(ok)

        // docs-for resolves bare names and returns BOTH signature and doc text.
        val docs = conn.request(IdeCommands.docsFor("double"), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("docs-for should succeed: ${docs.errorMessage}", docs.ok)
        val text = docs.text!!
        assertTrue("expected the type signature in: $text", text.contains("Nat -> Nat"))
        assertTrue("expected the doc comment text in: $text", text.contains("Doubles a natural number"))
    }

    /**
     * The protocol facts the documentation provider's disambiguation relies on:
     * `:type-of` at a caret returns the resolved `Qualified.Name : Type`, that
     * name is the header of the matching `:docs-for` block, and `:docs-for`
     * accepts ONLY the bare name (qualified queries are rejected). If any of
     * these change, IdrisDocumentationProvider must be revisited.
     */
    @Test
    fun `type-of at caret resolves the ambiguous name that docs-for blocks are keyed by`() {
        val conn = connect()
        writeSource(
            "Amb.idr",
            """
            module Amb
            import Data.List

            xs : List Nat
            xs = [1, 2, 3]

            myLen : Nat
            myLen = List.length xs
            """.trimIndent() + "\n",
        )
        val (ok, _, _) = load(conn, "Amb.idr")
        assertTrue(ok)

        // `length` is on 0-based line 7; the occurrence spans cols 8..19.
        val typeOf = conn.request(IdeCommands.typeOfAt("length", 8, 13), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("type-of should succeed: ${typeOf.errorMessage}", typeOf.ok)
        val resolved = typeOf.text!!
        assertEquals("Prelude.List.length : List a -> Nat", resolved)
        val qualifiedName = resolved.substringBefore(" : ").trim()

        // Bare docs-for returns MANY same-named blocks (the noise we filter).
        val docs = conn.request(IdeCommands.docsFor("length"), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("docs-for(bare) should succeed", docs.ok)
        val headers = docs.text!!.lines().filter { it.isNotEmpty() && !it[0].isWhitespace() }
        assertTrue("expected several same-named blocks, got: $headers", headers.size >= 3)
        assertTrue("expected our resolved name among the block headers: $headers",
            headers.any { it.substringBefore(" : ").trim() == qualifiedName })

        // Qualified docs-for is rejected, which is why we must filter bare output.
        val qualified = conn.request(IdeCommands.docsFor(qualifiedName), 30_000).get(30, TimeUnit.SECONDS)
        assertFalse("qualified docs-for is expected to fail", qualified.ok)

        // End-to-end: the provider's filter keeps exactly the resolved block.
        val block = io.github.mmhelloworld.idrisintellij.navigation.IdrisDocumentationProvider
            .selectDocBlock(docs.text!!, qualifiedName)
        assertEquals(
            "Prelude.List.length : List a -> Nat\n" +
                "  Returns the length of the list.\n" +
                "  Totality: total\n" +
                "  Visibility: public export",
            block,
        )
    }

    @Test
    fun `name-at returns absolute definition locations`() {
        val conn = connect()
        writeSource(
            "Defs.idr",
            """
            module Defs

            triple : Nat -> Nat
            triple n = 3 * n

            useTriple : Nat
            useTriple = triple 7
            """.trimIndent() + "\n",
        )
        val (ok, _, _) = load(conn, "Defs.idr")
        assertTrue(ok)
        // name-at resolves BARE names; the result reports the qualified name
        val result = conn.request(IdeCommands.nameAt("triple"), 30_000).get(30, TimeUnit.SECONDS)
        assertTrue("name-at should succeed: ${result.errorMessage}", result.ok)
        val locations = io.github.mmhelloworld.idrisintellij.protocol.ResultDecoder.parseNameLocations(result.payload)
        assertTrue("expected at least one location", locations.isNotEmpty())
        assertEquals("Defs.triple", locations[0].name)
        assertTrue("path should be absolute: ${locations[0].span.file}", File(locations[0].span.file).isAbsolute)
        assertEquals("definition is on 0-based line 2", 2, locations[0].span.startLine)
    }
}
