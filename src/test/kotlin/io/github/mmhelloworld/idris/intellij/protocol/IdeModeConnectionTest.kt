package io.github.mmhelloworld.idris.intellij.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.PipedReader
import java.io.PipedWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Drives [IdeModeConnection] against a fake ide-mode server speaking over pipes.
 */
class IdeModeConnectionTest {

    private lateinit var serverInput: PipedReader // what the client wrote
    private lateinit var serverOutput: PipedWriter // what the server sends
    private lateinit var connection: IdeModeConnection
    private lateinit var serverReader: FrameReader

    @Before
    fun setUp() {
        val clientToServer = PipedWriter()
        serverInput = PipedReader(clientToServer, 1 shl 16)
        serverOutput = PipedWriter()
        val clientInput = PipedReader(serverOutput, 1 shl 16)
        connection = IdeModeConnection(clientInput, clientToServer, "test")
        serverReader = FrameReader(serverInput)
    }

    @After
    fun tearDown() {
        connection.close()
    }

    private fun serverSend(text: String) {
        serverOutput.write(IdeModeFraming.encode(SExpParser.parse(text)))
        serverOutput.flush()
    }

    @Test(timeout = 10_000)
    fun `completes greeting future`() {
        serverSend("(:protocol-version 2 1)")
        val greeting = connection.greeting.get(5, TimeUnit.SECONDS)
        assertEquals(2L, greeting.major)
    }

    @Test(timeout = 10_000)
    fun `correlates return with request and routes async messages`() {
        serverSend("(:protocol-version 2 1)")
        connection.greeting.get(5, TimeUnit.SECONDS)

        val warnings = CopyOnWriteArrayList<IdrisDiagnostic>()
        val listener = object : AsyncReplyListener {
            override fun onWarning(diagnostic: IdrisDiagnostic) {
                warnings.add(diagnostic)
            }
        }
        val future = connection.request(IdeCommands.loadFile("Main.idr"), 5000, listener)

        // The server sees `((:load-file "Main.idr") 1)`
        val received = SExpParser.parse(serverReader.readFrame()!!)
        assertEquals("((:load-file \"Main.idr\") 1)", received.render())

        serverSend("(:warning (\"Main.idr\" (0 0) (0 3) \"Warning: shadowing\" ()) 1)")
        serverSend("(:return (:ok ()) 1)")

        val result = future.get(5, TimeUnit.SECONDS)
        assertTrue(result.ok)
        assertEquals(1, warnings.size)
        assertEquals("Warning: shadowing", warnings[0].message)
    }

    @Test(timeout = 10_000)
    fun `serializes requests - second is not written until first returns`() {
        serverSend("(:protocol-version 2 1)")
        connection.greeting.get(5, TimeUnit.SECONDS)

        val first = connection.request(IdeCommands.typeOf("a"), 5000)
        val second = connection.request(IdeCommands.typeOf("b"), 5000)

        // Only the first request is on the wire so far
        assertEquals("((:type-of \"a\") 1)", SExpParser.parse(serverReader.readFrame()!!).render())
        repeat(IdeModeFraming.SYNC_TRAILER.length) { serverInput.read() }
        assertTrue(!serverInput.ready())

        serverSend("(:return (:ok \"a : A\") 1)")
        assertEquals("a : A", first.get(5, TimeUnit.SECONDS).text)

        // Now the second arrives
        assertEquals("((:type-of \"b\") 2)", SExpParser.parse(serverReader.readFrame()!!).render())
        serverSend("(:return (:ok \"b : B\") 2)")
        assertEquals("b : B", second.get(5, TimeUnit.SECONDS).text)
    }

    @Test(timeout = 10_000)
    fun `request fails when connection closes`() {
        serverSend("(:protocol-version 2 1)")
        connection.greeting.get(5, TimeUnit.SECONDS)
        val future = connection.request(IdeCommands.typeOf("a"), 5000)
        serverReader.readFrame()
        serverOutput.close() // server dies
        try {
            future.get(5, TimeUnit.SECONDS)
            throw AssertionError("expected failure")
        } catch (e: java.util.concurrent.ExecutionException) {
            // expected
        }
        assertTrue(!connection.isAlive)
    }

    @Test(timeout = 15_000)
    fun `timeout fails the request and closes the session`() {
        serverSend("(:protocol-version 2 1)")
        connection.greeting.get(5, TimeUnit.SECONDS)
        val future = connection.request(IdeCommands.typeOf("a"), 300)
        try {
            future.get(5, TimeUnit.SECONDS)
            throw AssertionError("expected timeout")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is java.util.concurrent.TimeoutException)
        }
        assertTrue(!connection.isAlive)
    }
}
