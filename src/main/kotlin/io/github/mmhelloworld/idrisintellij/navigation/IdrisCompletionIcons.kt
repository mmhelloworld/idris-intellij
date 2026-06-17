package io.github.mmhelloworld.idrisintellij.navigation

import com.intellij.icons.AllIcons
import io.github.mmhelloworld.idrisintellij.lang.IdrisIcons
import javax.swing.Icon

/**
 * Maps a compiler decoration symbol (from the cached semantic highlights) to a
 * completion icon and a short kind label shown on the right of the lookup item.
 * The decoration set mirrors [io.github.mmhelloworld.idrisintellij.lang.IdrisColors.forDecoration]
 * so the two stay consistent; an unknown/absent decoration falls back to the
 * plain Idris file icon and an empty label.
 */
object IdrisCompletionIcons {

    data class Kind(val icon: Icon, val label: String)

    fun forDecoration(decor: String?): Kind = when (decor) {
        "function" -> Kind(AllIcons.Nodes.Function, "function")
        "data" -> Kind(AllIcons.Nodes.Field, "data")
        "type" -> Kind(AllIcons.Nodes.Class, "type")
        "bound" -> Kind(AllIcons.Nodes.Parameter, "local")
        "namespace" -> Kind(AllIcons.Nodes.Package, "namespace")
        "module" -> Kind(AllIcons.Nodes.Package, "module")
        "postulate" -> Kind(AllIcons.Nodes.Static, "postulate")
        else -> Kind(IdrisIcons.FILE, "")
    }
}
