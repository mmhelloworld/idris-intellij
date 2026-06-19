package io.github.mmhelloworld.idrisintellij.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Invalidates the [IdrisIdeService] caches when the compiler's build outputs
 * change on disk, so a project picks up a dependency rebuilt outside the IDE
 * (e.g. `idris2 --build`/`--install` of another package from the command line).
 *
 * Without this, a cached `:load-file` result for a file whose own modification
 * stamp never changed survives until the file is edited or the IDE restarts —
 * which is exactly the reported symptom: a dependent project not reflecting an
 * upstream rebuild until the IDE is restarted.
 *
 * Keyed on `.ttc`/`.ttm` (the compiler writes both when it (re)builds a module),
 * which is the precise signal a dependent load needs. Plain source edits are
 * already covered by the modification-stamp check in [IdrisIdeService.cachedLoad],
 * so they are deliberately ignored here to avoid pointless reloads.
 *
 * Registered per project via `projectListeners` on the application-wide
 * `VFS_CHANGES` topic; the events fire after a VFS refresh notices the external
 * change (on frame activation by default), i.e. when the user returns to the IDE.
 */
class IdrisBuildOutputListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        val touchedBuildOutput = events.any { event ->
            val path = event.path
            path.endsWith(".ttc") || path.endsWith(".ttm")
        }
        if (!touchedBuildOutput) return
        // Don't spin up the service just to clear empty caches: if no Idris file
        // has been loaded yet there is nothing to invalidate.
        project.getServiceIfCreated(IdrisIdeService::class.java)?.invalidateBuildCaches()
    }
}
