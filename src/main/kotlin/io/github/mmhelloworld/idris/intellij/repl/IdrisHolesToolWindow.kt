package io.github.mmhelloworld.idris.intellij.repl

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import io.github.mmhelloworld.idris.intellij.ide.IdrisIdeService
import io.github.mmhelloworld.idris.intellij.ide.LoadResult
import io.github.mmhelloworld.idris.intellij.lang.IdrisLanguage
import io.github.mmhelloworld.idris.intellij.protocol.Hole
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class IdrisHolesToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = IdrisHolesPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Lists the loaded file's holes (`:metavariables`) with their types and
 * premises; refreshes after every load. Double-click navigates to the first
 * occurrence of `?name` in the file (the protocol reports no hole locations).
 */
class IdrisHolesPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {

    private class HoleNode(val hole: Hole, val file: VirtualFile) :
        DefaultMutableTreeNode("${shortName(hole.name)} : ${hole.type}") {
        companion object {
            fun shortName(qualified: String): String = qualified.substringAfterLast('.')
        }
    }

    private val root = DefaultMutableTreeNode("No Idris file loaded yet")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)

    @Volatile
    private var currentFile: VirtualFile? = null

    init {
        tree.isRootVisible = true
        val center = JPanel(BorderLayout())
        center.add(JScrollPane(tree), BorderLayout.CENTER)
        setContent(center)

        val actions = DefaultActionGroup(RefreshAction())
        val actionToolbar = ActionManager.getInstance().createActionToolbar("IdrisHoles", actions, false)
        actionToolbar.targetComponent = center
        setToolbar(actionToolbar.component)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? HoleNode
                    ?: (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.parent as? HoleNode
                    ?: return
                navigateToHole(node)
            }
        })

        project.messageBus.connect().subscribe(
            IdrisIdeService.LOAD_FINISHED,
            IdrisIdeService.IdrisLoadListener { file, result -> onLoadFinished(file, result) },
        )
    }

    private fun onLoadFinished(file: VirtualFile, result: LoadResult) {
        if (!result.success) {
            showMessage("${file.name}: fix compile errors to list holes")
            return
        }
        currentFile = file
        ApplicationManager.getApplication().executeOnPooledThread {
            val holes = IdrisIdeService.getInstance(project).holesFor(file)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                renderHoles(file, holes)
            }
        }
    }

    private fun renderHoles(file: VirtualFile, holes: List<Hole>?) {
        root.removeAllChildren()
        when {
            holes == null -> root.userObject = "${file.name}: holes unavailable"
            holes.isEmpty() -> root.userObject = "${file.name}: no holes 🎉"
            else -> {
                root.userObject = "${file.name} — ${holes.size} hole(s)"
                for (hole in holes) {
                    val holeNode = HoleNode(hole, file)
                    for (premise in hole.premises) {
                        holeNode.add(DefaultMutableTreeNode("${premise.name} : ${premise.type}"))
                    }
                    root.add(holeNode)
                }
            }
        }
        treeModel.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun showMessage(message: String) {
        ApplicationManager.getApplication().invokeLater {
            root.removeAllChildren()
            root.userObject = message
            treeModel.reload()
        }
    }

    private fun navigateToHole(node: HoleNode) {
        val document = FileDocumentManager.getInstance().getDocument(node.file) ?: return
        val target = "?" + HoleNode.shortName(node.hole.name)
        val offset = document.charsSequence.indexOf(target)
        if (offset >= 0) {
            OpenFileDescriptor(project, node.file, offset).navigate(true)
        } else {
            OpenFileDescriptor(project, node.file).navigate(true)
        }
    }

    private inner class RefreshAction :
        AnAction("Refresh Holes", "Reload the active Idris file and list its holes", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull { vf ->
                val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(vf)
                psi?.language?.isKindOf(IdrisLanguage) == true
            } ?: currentFile ?: return
            FileDocumentManager.getInstance().saveAllDocuments()
            ApplicationManager.getApplication().executeOnPooledThread {
                IdrisIdeService.getInstance(project).ensureLoaded(file)
                // LOAD_FINISHED listener renders the result
            }
        }
    }
}
