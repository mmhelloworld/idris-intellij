package io.github.mmhelloworld.idris.intellij.protocol

import java.io.Reader
import java.io.Writer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Final outcome of one request. */
data class IdeResult(val ok: Boolean, val payload: SExp, val errorMessage: String?) {
    val text: String? get() = payload.asString
}

/** Receives the async messages that arrive before a request's final `:return`. */
interface AsyncReplyListener {
    fun onWarning(diagnostic: IdrisDiagnostic) {}
    fun onHighlights(spans: List<SemanticSpan>) {}
    fun onWriteString(text: String) {}
}

/**
 * One ide-mode session over a pair of character streams.
 *
 * The server processes requests strictly sequentially (Idris/IDEMode/REPL.idr
 * `loop`), so requests are serialized through a single-threaded executor: the
 * next request is not written until the previous one's `:return` arrives.
 *
 * Thread-safety: [request] may be called from any thread; futures complete on
 * internal threads — callers must hop to the EDT themselves if needed.
 */
class IdeModeConnection(
    input: Reader,
    private val output: Writer,
    private val name: String = "idris2",
    private val onClosed: (Throwable?) -> Unit = {},
) : AutoCloseable {

    private class Pending(
        val future: CompletableFuture<IdeResult>,
        val listener: AsyncReplyListener?,
    )

    private val frameReader = FrameReader(input)
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val closed = AtomicBoolean(false)
    @Volatile
    private var closeCause: Throwable? = null

    val greeting: CompletableFuture<Reply.ProtocolVersion> = CompletableFuture()

    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "$name-writer").apply { isDaemon = true }
    }

    private val readerThread = Thread({ readLoop() }, "$name-reader").apply {
        isDaemon = true
        start()
    }

    val isAlive: Boolean get() = !closed.get()

    /**
     * Sends `(<command> <id>)` and returns a future for the final `:return`.
     * Async messages with the same request id are routed to [listener].
     */
    fun request(
        command: SExp,
        timeoutMs: Long,
        listener: AsyncReplyListener? = null,
    ): CompletableFuture<IdeResult> {
        val result = CompletableFuture<IdeResult>()
        if (closed.get()) {
            result.completeExceptionally(IllegalStateException("Connection closed", closeCause))
            return result
        }
        writerExecutor.execute {
            if (closed.get()) {
                result.completeExceptionally(IllegalStateException("Connection closed", closeCause))
                return@execute
            }
            val id = requestIds.getAndIncrement()
            pending[id] = Pending(result, listener)
            try {
                output.write(IdeModeFraming.encodeRequest(command, id))
                output.flush()
                // Hold the writer thread until this request completes so requests
                // are never interleaved on the sequential server.
                try {
                    result.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    result.completeExceptionally(
                        TimeoutException("Idris request timed out after ${timeoutMs}ms: ${command.render()}"))
                    // Server state is unknown after a timeout; drop the session.
                    close(e)
                } catch (_: ExecutionException) {
                    // Failure is already propagated through the future.
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
                close(e)
            } finally {
                pending.remove(id)
            }
        }
        return result
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val frame = frameReader.readFrame() ?: break
                val reply = try {
                    ReplyDecoder.decode(SExpParser.parse(frame))
                } catch (e: SExpParseException) {
                    Reply.Unknown(SExp.SString(frame))
                }
                dispatch(reply)
            }
            close(null)
        } catch (e: Exception) {
            close(e)
        }
    }

    private fun dispatch(reply: Reply) {
        when (reply) {
            is Reply.ProtocolVersion -> greeting.complete(reply)
            is Reply.Return -> pending[reply.id]?.future
                ?.complete(IdeResult(reply.ok, reply.payload, reply.errorMessage))
            is Reply.Warning -> pending[reply.id]?.listener?.onWarning(reply.diagnostic)
            is Reply.Highlights -> pending[reply.id]?.listener?.onHighlights(reply.spans)
            is Reply.WriteString -> pending[reply.id]?.listener?.onWriteString(reply.text)
            is Reply.SetPrompt -> {}
            is Reply.Unknown -> {}
        }
    }

    fun close(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        closeCause = cause
        val error = IllegalStateException("Idris ide-mode connection closed", cause)
        greeting.completeExceptionally(error)
        pending.values.forEach { it.future.completeExceptionally(error) }
        pending.clear()
        writerExecutor.shutdown()
        try {
            output.close()
        } catch (_: Exception) {
        }
        onClosed(cause)
    }

    override fun close() = close(null)
}
