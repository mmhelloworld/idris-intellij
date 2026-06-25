package io.github.mmhelloworld.idrisintellij.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.messages.Topic
import io.github.mmhelloworld.idrisintellij.protocol.AsyncReplyListener
import io.github.mmhelloworld.idrisintellij.protocol.IdeCommands
import io.github.mmhelloworld.idrisintellij.protocol.IdeResult
import io.github.mmhelloworld.idrisintellij.protocol.IdrisDiagnostic
import io.github.mmhelloworld.idrisintellij.protocol.NameLocation
import io.github.mmhelloworld.idrisintellij.protocol.SExp
import io.github.mmhelloworld.idrisintellij.protocol.SemanticSpan
import io.github.mmhelloworld.idrisintellij.settings.IdrisSettings
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Snapshot of one successful or failed `:load-file` for a single source file. */
data class LoadResult(
    val success: Boolean,
    val diagnostics: List<IdrisDiagnostic>,
    val highlights: List<SemanticSpan>,
    val modStamp: Long,
    val errorMessage: String?,
)

/**
 * Project-level entry point to the ide-mode compiler sessions.
 *
 * One `idris2 --ide-mode` process per ipkg root; requests are serialized per
 * process by [io.github.mmhelloworld.idrisintellij.protocol.IdeModeConnection].
 * Every feature goes through [ensureLoaded] first because the compiler holds a
 * single loaded file. All methods are safe to call from background threads and
 * must NOT be called on the EDT (they may block on the compiler).
 */
@Service(Service.Level.PROJECT)
class IdrisIdeService(private val project: Project) : Disposable {

    private val handles = ConcurrentHashMap<Path, IdrisProcessHandle>()
    private val loadCache = ConcurrentHashMap<String, LoadResult>()
    // Last load that actually produced highlights. Survives subsequent dirty/failed-parse loads, so
    // completion can keep resolving names while you are mid-edit (when `loadCache` is stale or empty).
    private val lastGoodLoadCache = ConcurrentHashMap<String, LoadResult>()
    private val inFlightLoads = ConcurrentHashMap<String, CompletableFuture<LoadResult>>()
    private val definitionCache = ConcurrentHashMap<String, List<NameLocation>>()
    private val consecutiveStartFailures = AtomicInteger(0)
    private val lastTimeoutNotice = java.util.concurrent.atomic.AtomicLong(0)

    companion object {
        private val LOG = Logger.getInstance(IdrisIdeService::class.java)
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val TIMEOUT_NOTICE_INTERVAL_MS = 5 * 60_000L

        fun getInstance(project: Project): IdrisIdeService =
            project.getService(IdrisIdeService::class.java)

        /** Fired on the project bus after every completed `:load-file`. */
        @JvmField
        val LOAD_FINISHED: Topic<IdrisLoadListener> =
            Topic.create("Idris load finished", IdrisLoadListener::class.java)
    }

    fun interface IdrisLoadListener {
        fun loadFinished(file: VirtualFile, result: LoadResult)
    }

    /**
     * Where the last proof-search/generate-def result was applied, so the
     * `-next` commands know what text to swap out. The server keeps the
     * iterator; we keep the document range.
     */
    data class LastSearchEdit(
        val filePath: String,
        val nextCommand: SExp,
        val marker: com.intellij.openapi.editor.RangeMarker,
    )

    @Volatile
    var lastSearchEdit: LastSearchEdit? = null

    fun rootFor(file: VirtualFile): Path = IdrisRootResolver.rootFor(project, file)

    /**
     * Loads [file] into the compiler unless the cached result is still fresh.
     * Fresh = same modification stamp AND still the process's loaded file.
     */
    fun ensureLoaded(file: VirtualFile): CompletableFuture<LoadResult> {
        val path = file.path
        val stamp = currentStamp(file)
        val root = rootFor(file)

        cachedLoad(file)?.let { return CompletableFuture.completedFuture(it) }
        inFlightLoads[path]?.let { return it }

        val handle = try {
            handleFor(root)
        } catch (e: Exception) {
            return CompletableFuture.failedFuture(e)
        }

        val diagnostics = CopyOnWriteArrayList<IdrisDiagnostic>()
        val highlights = CopyOnWriteArrayList<SemanticSpan>()
        val listener = object : AsyncReplyListener {
            override fun onWarning(diagnostic: IdrisDiagnostic) {
                diagnostics.add(diagnostic)
            }

            override fun onHighlights(spans: List<SemanticSpan>) {
                highlights.addAll(spans)
            }

            override fun onWriteString(text: String) {
                // Build progress like "3/7: Building Org.Springframework.Boot (...)"
                setStatusBarInfo("Idris: $text")
            }
        }

        val relativePath = IdrisRootResolver.relativePath(root, file)
        val idleTimeoutMs = IdrisSettings.getInstance().loadTimeoutSeconds * 1000L
        val future = handle.connection
            .request(IdeCommands.loadFile(relativePath), idleTimeoutMs, listener)
            .thenApply { result ->
                LoadResult(result.ok, diagnostics.toList(), highlights.toList(), stamp, result.errorMessage)
                    .also { load ->
                        loadCache[path] = load
                        if (load.highlights.isNotEmpty()) lastGoodLoadCache[path] = load
                        handle.loadedFilePath = path
                        definitionCache.clear()
                    }
            }
        inFlightLoads[path] = future
        future.whenComplete { load, error ->
            inFlightLoads.remove(path)
            setStatusBarInfo(if (load?.success == false) "Idris: ${file.name} has errors" else "")
            if (error != null && isTimeout(error)) {
                notifyLoadTimeout(file.name)
            }
            if (load != null && !project.isDisposed) {
                lastSearchEdit = null // server-side search iterators reset on load
                project.messageBus.syncPublisher(LOAD_FINISHED).loadFinished(file, load)
            }
        }
        return future
    }

    /** Holes of the (already loaded) [file]; null on any failure. */
    fun holesFor(file: VirtualFile, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<io.github.mmhelloworld.idrisintellij.protocol.Hole>? {
        if (cachedLoad(file) == null) return null
        val result = try {
            rawRequest(rootFor(file), IdeCommands.metavariables(), timeoutMs)
                .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            LOG.debug("metavariables failed", e)
            return null
        }
        if (!result.ok) return null
        return io.github.mmhelloworld.idrisintellij.protocol.ResultDecoder.parseHoles(result.payload)
    }

    private fun isTimeout(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is java.util.concurrent.TimeoutException }

    private fun setStatusBarInfo(text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                WindowManager.getInstance().getStatusBar(project)?.info = text
            }
        }
    }

    /** At most one balloon per [TIMEOUT_NOTICE_INTERVAL_MS], or it would fire on every retry. */
    private fun notifyLoadTimeout(fileName: String) {
        val now = System.currentTimeMillis()
        val previous = lastTimeoutNotice.get()
        if (now - previous < TIMEOUT_NOTICE_INTERVAL_MS || !lastTimeoutNotice.compareAndSet(previous, now)) return
        val idleSeconds = IdrisSettings.getInstance().loadTimeoutSeconds
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Idris")
                .createNotification(
                    "Idris compiler unresponsive",
                    "Loading $fileName: no compiler output for ${idleSeconds}s and the idris2 process " +
                        "shows no CPU activity — it looks hung, so the session was dropped. " +
                        "It will restart on the next request.",
                    NotificationType.WARNING,
                )
                .addAction(NotificationAction.createSimple("Open Idris settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Idris 2")
                })
                .notify(project)
        }
    }

    /** The cached load result for [file], or null if missing or stale. */
    fun cachedLoad(file: VirtualFile): LoadResult? {
        val cached = loadCache[file.path] ?: return null
        if (cached.modStamp != currentStamp(file)) return null
        val handle = handles[rootFor(file)] ?: return null
        if (!handle.isAlive || handle.loadedFilePath != file.path) return null
        return cached
    }

    /**
     * The most recent load that produced highlights, IGNORING modification staleness — for
     * best-effort completion while the file is dirty (an in-progress edit, so `cachedLoad` is null,
     * or a transient parse error, so the latest load has no highlights). Names/namespaces from the
     * last good state are still the right basis for resolving a completion receiver; positions may be
     * off, but completion does not rely on them. Still requires this file to be the one loaded.
     */
    fun lastGoodLoad(file: VirtualFile): LoadResult? {
        val cached = lastGoodLoadCache[file.path] ?: return null
        val handle = handles[rootFor(file)] ?: return null
        if (!handle.isAlive || handle.loadedFilePath != file.path) return null
        return cached
    }

    /** Loads [file] first, then runs [command] against it. */
    fun request(
        file: VirtualFile,
        command: SExp,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        listener: AsyncReplyListener? = null,
    ): CompletableFuture<IdeResult> =
        ensureLoaded(file).thenCompose { load ->
            if (!load.success) {
                CompletableFuture.failedFuture(
                    IllegalStateException(load.errorMessage ?: "File failed to load"))
            } else {
                rawRequest(rootFor(file), command, timeoutMs, listener)
            }
        }

    /** Runs [command] on the process for [root] without (re)loading anything. */
    fun rawRequest(
        root: Path,
        command: SExp,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        listener: AsyncReplyListener? = null,
    ): CompletableFuture<IdeResult> {
        val handle = try {
            handleFor(root)
        } catch (e: Exception) {
            return CompletableFuture.failedFuture(e)
        }
        return handle.connection.request(command, timeoutMs, listener)
    }

    /**
     * `(:name-at name)` with a per-load memo; results carry absolute paths.
     * The server resolves BARE names only (a qualified query returns nothing)
     * and reports each hit's fully qualified name, so we send [bareName] and
     * filter by [qualifiedName] when the caller knows it.
     */
    fun definitionsFor(root: Path, bareName: String, qualifiedName: String?, timeoutMs: Long): List<NameLocation>? {
        val cacheKey = qualifiedName ?: bareName
        definitionCache[cacheKey]?.let { return it }
        val result = try {
            rawRequest(root, IdeCommands.nameAt(bareName), timeoutMs).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            LOG.debug("name-at failed for $bareName", e)
            return null
        }
        if (!result.ok) return null
        val all = io.github.mmhelloworld.idrisintellij.protocol.ResultDecoder.parseNameLocations(result.payload)
        val locations = all.filter { qualifiedName == null || it.name == qualifiedName }.ifEmpty { all }
        definitionCache[cacheKey] = locations
        return locations
    }

    @Synchronized
    private fun handleFor(root: Path): IdrisProcessHandle {
        handles[root]?.let { if (it.isAlive) return it else removeHandle(root) }
        if (consecutiveStartFailures.get() >= 3) {
            throw IllegalStateException(
                "idris2 failed to start ${consecutiveStartFailures.get()} times; fix the path in Settings | Languages & Frameworks | Idris 2 and use 'Restart Idris'")
        }
        val settings = IdrisSettings.getInstance()
        val extraArgs = settings.extraArgs.split(' ').filter { it.isNotBlank() }
        return try {
            val handle = IdrisProcessHandle.start(settings.resolveExecutable(), extraArgs, root) { cause ->
                if (cause != null) LOG.warn("idris2 connection for $root closed", cause)
                removeHandle(root)
            }
            consecutiveStartFailures.set(0)
            handles[root] = handle
            handle
        } catch (e: Exception) {
            if (consecutiveStartFailures.incrementAndGet() >= 3) {
                notifyStartupFailure(e)
            }
            throw e
        }
    }

    private fun removeHandle(root: Path) {
        handles.remove(root)
        val orphaned = { path: String -> handles.values.none { it.loadedFilePath == path } }
        loadCache.keys.removeIf(orphaned)
        lastGoodLoadCache.keys.removeIf(orphaned)
    }

    private fun notifyStartupFailure(cause: Exception) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Idris")
                .createNotification(
                    "Idris compiler unavailable",
                    "Could not start idris2 in ide-mode: ${cause.message}",
                    NotificationType.ERROR,
                )
                .notify(project)
        }
    }

    /**
     * Drops cached `:load-file` and `:name-at` results so the next request
     * reloads from the compiler, without killing the running processes. Called
     * when build outputs change on disk (see [IdrisBuildOutputListener]).
     *
     * The freshness check in [cachedLoad] only looks at the dependent file's own
     * modification stamp, so a dependency rebuilt outside the IDE would never be
     * picked up until that file is edited or the IDE restarts. A live ide-mode
     * process does recompile against a rebuilt dependency TTC on the next
     * `:load-file`, so clearing the cache is enough — no process restart needed.
     */
    fun invalidateBuildCaches() {
        loadCache.clear()
        lastGoodLoadCache.clear()
        definitionCache.clear()
    }

    /** Kills all compiler processes; they restart lazily on the next request. */
    fun restart() {
        val current = handles.values.toList()
        handles.clear()
        loadCache.clear()
        lastGoodLoadCache.clear()
        definitionCache.clear()
        consecutiveStartFailures.set(0)
        ApplicationManager.getApplication().executeOnPooledThread {
            current.forEach { it.destroy() }
        }
    }

    private fun currentStamp(file: VirtualFile): Long {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        return document?.modificationStamp ?: file.modificationStamp
    }

    override fun dispose() {
        val current = handles.values.toList()
        handles.clear()
        current.forEach { it.destroy() }
    }
}
