package io.github.mmhelloworld.idrisintellij.repl

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import io.github.mmhelloworld.idrisintellij.ide.IdrisIdeService
import io.github.mmhelloworld.idrisintellij.lang.IdrisFileType
import io.github.mmhelloworld.idrisintellij.protocol.AsyncReplyListener
import io.github.mmhelloworld.idrisintellij.protocol.IdeCommands
import java.awt.BorderLayout
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JPanel

class IdrisReplToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = IdrisReplPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel.console)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * A REPL multiplexed over the ide-mode connection (`:interpret`). Expressions
 * are evaluated in the context of the most recently loaded file — use the
 * "Load Current File" toolbar action to bring the active editor's module in.
 */
class IdrisReplPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {

    val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val input = JBTextField()
    private val history = ArrayList<String>()
    private var historyIndex = 0

    init {
        val center = JPanel(BorderLayout())
        center.add(console.component, BorderLayout.CENTER)
        center.add(input, BorderLayout.SOUTH)
        setContent(center)

        val actions = DefaultActionGroup(LoadCurrentFileAction(), RestartAction())
        val actionToolbar = ActionManager.getInstance().createActionToolbar("IdrisRepl", actions, false)
        actionToolbar.targetComponent = center
        setToolbar(actionToolbar.component)

        input.emptyText.text = "Idris expression — Enter to evaluate"
        input.addActionListener {
            val expression = input.text.trim()
            if (expression.isNotEmpty()) {
                history.add(expression)
                historyIndex = history.size
                input.text = ""
                evaluate(expression)
            }
        }
        input.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_UP -> {
                        if (historyIndex > 0) {
                            historyIndex--
                            input.text = history[historyIndex]
                        }
                    }
                    java.awt.event.KeyEvent.VK_DOWN -> {
                        if (historyIndex < history.size - 1) {
                            historyIndex++
                            input.text = history[historyIndex]
                        } else {
                            historyIndex = history.size
                            input.text = ""
                        }
                    }
                }
            }
        })
        console.print("Idris 2 REPL — expressions are evaluated via ide-mode :interpret\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private fun activeRoot(): Path {
        val service = IdrisIdeService.getInstance(project)
        val selected = FileEditorManager.getInstance(project).selectedFiles
            .firstOrNull { it.fileType == IdrisFileType || it.fileType == io.github.mmhelloworld.idrisintellij.lang.IdrisLiterateFileType }
        return if (selected != null) service.rootFor(selected)
        else Paths.get(project.basePath ?: ".")
    }

    private fun evaluate(expression: String) {
        console.print("λΠ> $expression\n", ConsoleViewContentType.USER_INPUT)
        val service = IdrisIdeService.getInstance(project)
        val root = activeRoot()
        ApplicationManager.getApplication().executeOnPooledThread {
            val listener = object : AsyncReplyListener {
                override fun onWriteString(text: String) {
                    printAsync(text + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                }
            }
            service.rawRequest(root, IdeCommands.interpret(expression), 60_000, listener)
                .whenComplete { result, error ->
                    when {
                        error != null ->
                            printAsync("Error: ${error.cause?.message ?: error.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                        result.ok -> {
                            val text = result.text
                            if (!text.isNullOrBlank()) printAsync(text + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }
                        else ->
                            printAsync((result.errorMessage ?: "error") + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
        }
    }

    private fun printAsync(text: String, contentType: ConsoleViewContentType) {
        ApplicationManager.getApplication().invokeLater {
            console.print(text, contentType)
        }
    }

    private inner class LoadCurrentFileAction :
        AnAction("Load Current File", "Load the active editor's file into the Idris REPL", AllIcons.Actions.Rerun) {
        override fun actionPerformed(e: AnActionEvent) {
            val file: VirtualFile = FileEditorManager.getInstance(project).selectedFiles
                .firstOrNull { it.fileType == IdrisFileType || it.fileType == io.github.mmhelloworld.idrisintellij.lang.IdrisLiterateFileType } ?: return
            FileDocumentManager.getInstance().saveAllDocuments()
            console.print("Loading ${file.name}...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            ApplicationManager.getApplication().executeOnPooledThread {
                IdrisIdeService.getInstance(project).ensureLoaded(file).whenComplete { load, error ->
                    when {
                        error != null ->
                            printAsync("Load failed: ${error.cause?.message ?: error.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                        load.success ->
                            printAsync("Loaded ${file.name} (${load.diagnostics.size} warning(s))\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        else ->
                            printAsync("Load failed: ${load.errorMessage ?: "see editor diagnostics"}\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
        }
    }

    private inner class RestartAction :
        AnAction("Restart Idris", "Restart all Idris compiler processes", AllIcons.Actions.Restart) {
        override fun actionPerformed(e: AnActionEvent) {
            IdrisIdeService.getInstance(project).restart()
            console.print("Idris processes restarted\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }
    }
}
