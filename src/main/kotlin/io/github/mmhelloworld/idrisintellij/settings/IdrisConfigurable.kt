package io.github.mmhelloworld.idrisintellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.mmhelloworld.idrisintellij.ide.IdrisIdeService
import io.github.mmhelloworld.idrisintellij.ide.IdrisProcessHandle
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class IdrisConfigurable : Configurable {

    private var panel: JPanel? = null
    private val pathField = TextFieldWithBrowseButton()
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(120, 5, 3600, 5))
    private val extraArgsField = JBTextField()

    override fun getDisplayName(): String = "Idris 2"

    override fun createComponent(): JComponent {
        pathField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    .withTitle("Select idris2 Executable")
                    .withDescription("Point at the idris2 launcher (for the JVM backend: <idris-jvm>/build/exec/idris2)"),
            ),
        )
        val testButton = JButton("Test Connection")
        testButton.addActionListener { testConnection() }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("idris2 executable:", pathField)
            .addTooltip("Leave empty to use idris2 from PATH. JAVA_OPTS is honored by the JVM launcher script.")
            .addLabeledComponent("Compiler idle timeout (seconds):", timeoutSpinner)
            .addTooltip("Requests fail after this long without compiler output IF the idris2 process is also idle (no CPU). A busy compiler is never timed out (1h hard cap).")
            .addLabeledComponent("Extra arguments:", extraArgsField)
            .addComponent(testButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = IdrisSettings.getInstance()
        return pathField.text != settings.idris2Path ||
            timeoutSpinner.value != settings.loadTimeoutSeconds ||
            extraArgsField.text != settings.extraArgs
    }

    override fun apply() {
        val settings = IdrisSettings.getInstance()
        val pathChanged = settings.idris2Path != pathField.text
        settings.idris2Path = pathField.text
        settings.loadTimeoutSeconds = timeoutSpinner.value as Int
        settings.extraArgs = extraArgsField.text
        if (pathChanged) {
            ProjectManager.getInstance().openProjects.forEach { project ->
                IdrisIdeService.getInstance(project).restart()
            }
            ApplicationManager.getApplication().messageBus
                .syncPublisher(IdrisSettings.SETTINGS_CHANGED).run()
        }
    }

    override fun reset() {
        val settings = IdrisSettings.getInstance()
        pathField.text = settings.idris2Path
        timeoutSpinner.value = settings.loadTimeoutSeconds
        extraArgsField.text = settings.extraArgs
    }

    /**
     * Spawns through [IdrisProcessHandle.start] — the SAME path real sessions
     * use — so the verdict here matches actual behavior. (A raw ProcessBuilder
     * would inherit the GUI app's environment, where the JVM launcher script
     * cannot find `java` on macOS; GeneralCommandLine inside the handle gets
     * IntelliJ's shell-sourced environment.) The greeting check, the
     * legacy-runtime stdio probe, and stderr capture all come for free.
     */
    private fun testConnection() {
        val executable = pathField.text.ifBlank { IdrisSettings.getInstance().resolveExecutable() }
        val extraArgs = extraArgsField.text.split(' ').filter { it.isNotBlank() }
        var handle: IdrisProcessHandle? = null
        var failure: Exception? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    handle = IdrisProcessHandle.start(
                        executable, extraArgs, Paths.get(System.getProperty("user.home"))) {}
                } catch (e: Exception) {
                    failure = e
                }
            },
            "Testing Idris connection", true, null,
        )
        val started = handle
        if (started != null) {
            ApplicationManager.getApplication().executeOnPooledThread { started.destroy() }
            Messages.showInfoMessage(
                panel,
                "Connected: ide-mode protocol version " +
                    "${started.protocolVersion.major}.${started.protocolVersion.minor}",
                "Idris 2",
            )
        } else if (failure != null) {
            Messages.showErrorDialog(
                panel,
                "Could not talk to '$executable':\n${failure.message}",
                "Idris 2",
            )
        }
        // Neither set: the user cancelled the progress dialog.
    }
}
