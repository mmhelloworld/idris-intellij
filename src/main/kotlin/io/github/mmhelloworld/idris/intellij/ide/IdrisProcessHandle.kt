package io.github.mmhelloworld.idris.intellij.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import io.github.mmhelloworld.idris.intellij.protocol.IdeModeConnection
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
         * greeting. Call from a background thread only.
         */
        fun start(executable: String, extraArgs: List<String>, root: Path, onClosed: (Throwable?) -> Unit): IdrisProcessHandle {
            val commandLine = GeneralCommandLine(listOf(executable) + extraArgs + "--ide-mode")
                .withWorkDirectory(root.toFile())
                .withCharset(StandardCharsets.UTF_8)
            val process = commandLine.createProcess()

            val stderrDrain = Thread({
                try {
                    BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).useLines { lines ->
                        lines.forEach { LOG.info("idris2 stderr: $it") }
                    }
                } catch (_: Exception) {
                }
            }, "idris2-stderr")
            stderrDrain.isDaemon = true
            stderrDrain.start()

            val connection = IdeModeConnection(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
                OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8),
                name = "idris2[${root.fileName}]",
                onClosed = onClosed,
                livenessProbe = cpuLivenessProbe(process),
            )
            try {
                val version = connection.greeting.get(30, TimeUnit.SECONDS)
                if (version.major != 2L) {
                    LOG.warn("Unexpected ide-mode protocol version ${version.major}.${version.minor}")
                }
            } catch (e: Exception) {
                connection.close()
                process.destroyForcibly()
                throw IllegalStateException(
                    "idris2 did not send the ide-mode greeting. Check the executable path in Settings | Languages & Frameworks | Idris 2.",
                    e,
                )
            }
            when (connection.probeRuntime()) {
                RuntimeProbe.HEALTHY -> {}
                RuntimeProbe.LEGACY_RUNTIME -> {
                    connection.close()
                    process.destroyForcibly()
                    throw IllegalStateException(
                        "This idris2 build ($executable) has the pre-0.8.2 JVM runtime stdio bug: " +
                            "ide-mode replies stall until more input arrives, so every request would time out. " +
                            "Point the plugin at idris2-jvm 0.8.2 or newer in Settings | Languages & Frameworks | Idris 2.")
                }
                RuntimeProbe.UNRESPONSIVE -> {
                    connection.close()
                    process.destroyForcibly()
                    throw IllegalStateException(
                        "idris2 ($executable) sent the ide-mode greeting but did not answer a probe command.")
                }
            }
            return IdrisProcessHandle(root, process, connection)
        }
    }
}
