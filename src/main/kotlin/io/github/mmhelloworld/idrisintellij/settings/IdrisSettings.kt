package io.github.mmhelloworld.idrisintellij.settings

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

/** Application-level settings: which `idris2` executable to talk to. */
@Service(Service.Level.APP)
@State(name = "IdrisSettings", storages = [Storage("idris2.xml")])
class IdrisSettings : PersistentStateComponent<IdrisSettings.State> {

    class State {
        var idris2Path: String = ""
        var loadTimeoutSeconds: Int = 120
        var extraArgs: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var idris2Path: String
        get() = state.idris2Path
        set(value) {
            state.idris2Path = value
        }

    var loadTimeoutSeconds: Int
        get() = state.loadTimeoutSeconds
        set(value) {
            state.loadTimeoutSeconds = value
        }

    var extraArgs: String
        get() = state.extraArgs
        set(value) {
            state.extraArgs = value
        }

    /** Configured path, or `idris2` discovered on PATH. */
    fun resolveExecutable(): String =
        state.idris2Path.ifBlank {
            PathEnvironmentVariableUtil.findInPath("idris2")?.absolutePath ?: "idris2"
        }

    companion object {
        @JvmStatic
        fun getInstance(): IdrisSettings =
            ApplicationManager.getApplication().getService(IdrisSettings::class.java)

        @JvmField
        val SETTINGS_CHANGED: Topic<Runnable> =
            Topic.create("Idris settings changed", Runnable::class.java)
    }
}
