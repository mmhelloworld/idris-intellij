package io.github.mmhelloworld.idris.intellij.ide

import io.github.mmhelloworld.idris.intellij.protocol.AsyncReplyListener
import io.github.mmhelloworld.idris.intellij.protocol.IdeCommands
import io.github.mmhelloworld.idris.intellij.protocol.IdeModeConnection
import io.github.mmhelloworld.idris.intellij.protocol.IdrisDiagnostic
import io.github.mmhelloworld.idris.intellij.protocol.SemanticSpan
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
        val locations = io.github.mmhelloworld.idris.intellij.protocol.ResultDecoder.parseNameLocations(result.payload)
        assertTrue("expected at least one location", locations.isNotEmpty())
        assertEquals("Defs.triple", locations[0].name)
        assertTrue("path should be absolute: ${locations[0].span.file}", File(locations[0].span.file).isAbsolute)
        assertEquals("definition is on 0-based line 2", 2, locations[0].span.startLine)
    }
}
