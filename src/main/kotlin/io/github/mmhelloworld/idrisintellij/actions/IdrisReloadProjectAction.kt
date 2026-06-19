package io.github.mmhelloworld.idrisintellij.actions

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import io.github.mmhelloworld.idrisintellij.ide.IdrisIdeService

/**
 * Re-syncs the IDE with the compiler so changes built outside the IDE are
 * picked up without restarting it — the manual counterpart to the automatic
 * [io.github.mmhelloworld.idrisintellij.ide.IdrisBuildOutputListener].
 *
 * The listener only sees build outputs under the project's watched content
 * roots; a dependency installed elsewhere (e.g. `idris2 --install` into the
 * package database) is invisible to it. This action covers that case, analogous
 * to "Reload Project" on a Maven module: it drops the cached `:load-file` /
 * `:name-at` results and re-runs analysis on the open editors. The live ide-mode
 * process re-reads changed dependency TTCs on the next `:load-file`, so the
 * compiler processes are left running — use "Restart Idris" for a full restart.
 */
class IdrisReloadProjectAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        IdrisIdeService.getInstance(project).invalidateBuildCaches()
        DaemonCodeAnalyzer.getInstance(project).restart()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Idris")
            .createNotification(
                "Idris project reloaded",
                "Re-reading the latest build outputs; open files will refresh against the compiler.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
