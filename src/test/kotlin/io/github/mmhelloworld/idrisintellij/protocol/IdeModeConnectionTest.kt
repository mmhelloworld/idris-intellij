package io.github.mmhelloworld.idrisintellij.protocol

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
        assertTrue(!serverInput.ready())

        serverSend("(:return (:ok \"a : A\") 1)")
        assertEquals("a : A", first.get(5, TimeUnit.SECONDS).text)

        // Now the second arrives
        assertEquals("((:type-of \"b\") 2)", SExpParser.parse(serverReader.readFrame()!!).render())
        serverSend("(:return (:ok \"b : B\") 2)")
        assertEquals("b : B", second.get(5, TimeUnit.SECONDS).text)
    }

    @Test(timeout = 10_000)
    fun `probe reports healthy when the server answers promptly`() {
        serverSend("(:protocol-version 2 1)")
        connection.greeting.get(5, TimeUnit.SECONDS)
        val responder = Thread {
            serverReader.readFrame()
            serverSend("(:return (:error \"Unrecognised command\") 1)")
        }
        responder.start()
        assertEquals(RuntimeProbe.HEALTHY, connection.probeRuntime(5000, 1000))
        responder.join()
    }

    @Test(timeout = 15_000)
    fun `probe reports legacy runtime when a reply needs an extra byte`() {
        serverSend("(:protocol-version 2 1)")
        connection.greeting.get(5, TimeUnit.SECONDS)
        // Simulate the pre-0.8.2 fEOF stall: read the probe frame, then hold
        // the reply hostage until one more character arrives.
        val responder = Thread {
            serverReader.readFrame()
            serverInput.read() // blocks until the client's unblocking "\n"
            serverSend("(:return (:error \"Unrecognised command\") 1)")
        }
        responder.start()
        assertEquals(RuntimeProbe.LEGACY_RUNTIME, connection.probeRuntime(800, 3000))
        responder.join()
    }

    @Test(timeout = 15_000)
    fun `probe reports unresponsive when nothing ever arrives`() {
        serverSend("(:protocol-version 2 1)")
        connection.greeting.get(5, TimeUnit.SECONDS)
        assertEquals(RuntimeProbe.UNRESPONSIVE, connection.probeRuntime(500, 500))
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

    @Test(timeout = 20_000)
    fun `progress activity resets the idle timeout`() {
        serverSend("(:protocol-version 2 1)")
        connection.greeting.get(5, TimeUnit.SECONDS)

        val progress = CopyOnWriteArrayList<String>()
        val listener = object : AsyncReplyListener {
            override fun onWriteString(text: String) {
                progress.add(text)
            }
        }
        // Idle limit 800ms, but the request takes ~1.5s total — progress
        // messages every ~300ms must keep it alive.
        val future = connection.request(IdeCommands.loadFile("Big.idr"), 800, listener)
        serverReader.readFrame()
        repeat(5) { i ->
            Thread.sleep(300)
            serverSend("(:write-string \"${i + 1}/5: Building M$i\" 1)")
        }
        serverSend("(:return (:ok ()) 1)")

        val result = future.get(5, TimeUnit.SECONDS)
        assertTrue(result.ok)
        assertEquals(5, progress.size)
        assertTrue(connection.isAlive)
    }

    @Test(timeout = 20_000)
    fun `liveness probe extends the idle timeout until it reports no work`() {
        // Separate connection so we can install a probe.
        val clientToServer = PipedWriter()
        val probeServerInput = PipedReader(clientToServer, 1 shl 16)
        val probeServerOutput = PipedWriter()
        val clientInput = PipedReader(probeServerOutput, 1 shl 16)
        val probeCalls = java.util.concurrent.atomic.AtomicInteger(0)
        // "Working" for the first 3 consultations, then idle.
        val probed = IdeModeConnection(clientInput, clientToServer, "probe-test",
            livenessProbe = { probeCalls.incrementAndGet() <= 3 })
        try {
            probeServerOutput.write(IdeModeFraming.encode(SExpParser.parse("(:protocol-version 2 1)")))
            probeServerOutput.flush()
            probed.greeting.get(5, TimeUnit.SECONDS)

            // 400ms idle limit, server totally silent: the probe should be
            // consulted ~4 times (3 extensions + 1 final) before failure.
            val future = probed.request(IdeCommands.typeOf("a"), 400)
            try {
                future.get(15, TimeUnit.SECONDS)
                throw AssertionError("expected timeout")
            } catch (e: java.util.concurrent.ExecutionException) {
                assertTrue(e.cause is java.util.concurrent.TimeoutException)
            }
            assertEquals(4, probeCalls.get())
            assertTrue(!probed.isAlive)
        } finally {
            probed.close()
        }
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
