package io.github.mmhelloworld.idris.intellij.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import io.github.mmhelloworld.idris.intellij.protocol.IdeModeConnection
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
            return IdrisProcessHandle(root, process, connection)
        }
    }
}
