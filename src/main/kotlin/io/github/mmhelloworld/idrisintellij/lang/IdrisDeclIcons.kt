package io.github.mmhelloworld.idrisintellij.lang

import com.intellij.icons.AllIcons
import javax.swing.Icon

/** Icon for a declaration kind, shared by the structure view and breadcrumbs. */
object IdrisDeclIcons {

    fun forKind(kind: IdrisDeclKind): Icon = when (kind) {
        IdrisDeclKind.MODULE, IdrisDeclKind.NAMESPACE, IdrisDeclKind.IMPORT -> AllIcons.Nodes.Package
        IdrisDeclKind.DATA -> AllIcons.Nodes.Enum
        IdrisDeclKind.RECORD -> AllIcons.Nodes.Class
        IdrisDeclKind.INTERFACE -> AllIcons.Nodes.Interface
        IdrisDeclKind.IMPLEMENTATION -> AllIcons.Nodes.Class
        IdrisDeclKind.FUNCTION, IdrisDeclKind.TYPE_SIG -> AllIcons.Nodes.Function
        IdrisDeclKind.OTHER -> IdrisIcons.FILE
    }
}
