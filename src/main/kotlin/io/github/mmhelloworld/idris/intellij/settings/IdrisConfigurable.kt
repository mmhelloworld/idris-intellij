package io.github.mmhelloworld.idris.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.mmhelloworld.idris.intellij.ide.IdrisIdeService
import io.github.mmhelloworld.idris.intellij.protocol.IdeModeConnection
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
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
            com.intellij.openapi.ui.TextBrowseFolderListener(
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
            .addLabeledComponent("Load timeout (seconds):", timeoutSpinner)
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

    private fun testConnection() {
        val executable = pathField.text.ifBlank { IdrisSettings.getInstance().resolveExecutable() }
        try {
            val process = ProcessBuilder(executable, "--ide-mode").start()
            val connection = IdeModeConnection(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
                OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8),
            )
            try {
                val greeting = connection.greeting.get(30, TimeUnit.SECONDS)
                Messages.showInfoMessage(
                    panel,
                    "Connected: ide-mode protocol version ${greeting.major}.${greeting.minor}",
                    "Idris 2",
                )
            } finally {
                connection.close()
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(
                panel,
                "Could not talk to '$executable':\n${e.cause?.message ?: e.message}",
                "Idris 2",
            )
        }
    }
}
