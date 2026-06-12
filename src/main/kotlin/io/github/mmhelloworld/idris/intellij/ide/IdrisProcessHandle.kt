package io.github.mmhelloworld.idris.intellij.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import io.github.mmhelloworld.idris.intellij.protocol.IdeModeConnection
import io.github.mmhelloworld.idris.intellij.protocol.Reply
import io.github.mmhelloworld.idris.intellij.protocol.RuntimeProbe
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * One `idris2 --ide-mode` process rooted at [root] (the directory whose `.ipkg`
 * governs module resolution). The compiler holds ONE loaded file at a time;
 * [loadedFilePath] tracks which.
 */
class IdrisProcessHandle private constructor(
    val root: Path,
    private val process: Process,
    val connection: IdeModeConnection,
    val protocolVersion: Reply.ProtocolVersion,
) {
    @Volatile
    var loadedFilePath: String? = null

    val isAlive: Boolean get() = process.isAlive && connection.isAlive

    fun destroy() {
        connection.close()
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(IdrisProcessHandle::class.java)
        private const val STDERR_TAIL_LINES = 10

        /**
         * "Still working" = the process is alive and its cumulative CPU time
         * advanced since the last probe. The compiler emits no protocol
         * messages while elaborating a single module, so this is what keeps
         * long cold builds from being mistaken for hangs. Where the platform
         * cannot report CPU time, fall back to plain liveness.
         *
         * Only called from the connection's writer thread, so the mutable
         * lastCpu needs no synchronization.
         */
        private fun cpuLivenessProbe(process: Process): () -> Boolean {
            var lastCpu: java.time.Duration? = null
            return probe@{
                if (!process.isAlive) return@probe false
                val cpu = process.toHandle().info().totalCpuDuration().orElse(null)
                    ?: return@probe true // no CPU info on this platform; alive is the best we have
                val advanced = lastCpu == null || cpu > lastCpu
                lastCpu = cpu
                advanced
            }
        }

        /**
         * Spawns the process and blocks (briefly) for the `(:protocol-version ...)`
         * greeting, then health-checks the runtime's stdio path. Call from a
         * background thread only.
         *
         * Uses [GeneralCommandLine] deliberately: it inherits IntelliJ's
         * shell-sourced environment, so the launcher script can find `java`
         * even though macOS GUI apps don't have the user's shell PATH.
         */
        fun start(executable: String, extraArgs: List<String>, root: Path, onClosed: (Throwable?) -> Unit): IdrisProcessHandle {
            val commandLine = GeneralCommandLine(listOf(executable) + extraArgs + "--ide-mode")
                .withWorkDirectory(root.toFile())
                .withCharset(StandardCharsets.UTF_8)
            val process = commandLine.createProcess()

            val recentStderr = ArrayDeque<String>()
            val stderrDrain = Thread({
                try {
                    BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).useLines { lines ->
                        lines.forEach { line ->
                            LOG.info("idris2 stderr: $line")
                            synchronized(recentStderr) {
                                recentStderr.addLast(line)
                                while (recentStderr.size > STDERR_TAIL_LINES) recentStderr.removeFirst()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }, "idris2-stderr")
            stderrDrain.isDaemon = true
            stderrDrain.start()

            fun fail(message: String, cause: Exception? = null): Nothing {
                // Give the drain a moment to pick up the dying process's output.
                if (!process.isAlive) Thread.sleep(300)
                val stderrTail = synchronized(recentStderr) { recentStderr.toList() }
                val detail = if (stderrTail.isEmpty()) "" else "\nidris2 stderr:\n" + stderrTail.joinToString("\n")
                throw IllegalStateException(message + detail, cause)
            }

            val connection = IdeModeConnection(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
                OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8),
                name = "idris2[${root.fileName}]",
                onClosed = onClosed,
                livenessProbe = cpuLivenessProbe(process),
            )
            val version = try {
                connection.greeting.get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                connection.close()
                process.destroyForcibly()
                fail(
                    "idris2 ($executable) did not send the ide-mode greeting. " +
                        "Check the executable path in Settings | Languages & Frameworks | Idris 2.",
                    e,
                )
            }
            if (version.major != 2L) {
                LOG.warn("Unexpected ide-mode protocol version ${version.major}.${version.minor}")
            }
            when (connection.probeRuntime()) {
                RuntimeProbe.HEALTHY -> {}
                RuntimeProbe.LEGACY_RUNTIME -> {
                    connection.close()
                    process.destroyForcibly()
                    fail(
                        "This idris2 build ($executable) has the pre-0.8.2 JVM runtime stdio bug: " +
                            "ide-mode replies stall until more input arrives, so every request would time out. " +
                            "Point the plugin at idris2-jvm 0.8.2 or newer in Settings | Languages & Frameworks | Idris 2.")
                }
                RuntimeProbe.UNRESPONSIVE -> {
                    connection.close()
                    process.destroyForcibly()
                    fail("idris2 ($executable) sent the ide-mode greeting but did not answer a probe command.")
                }
            }
            return IdrisProcessHandle(root, process, connection, version)
        }
    }
}
