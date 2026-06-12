package io.github.mmhelloworld.idris.intellij.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object IdrisIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/idris.svg", IdrisIcons::class.java)
}

object IdrisLanguage : Language("Idris") {
    private fun readResolve(): Any = IdrisLanguage
    override fun getDisplayName(): String = "Idris"
}

object IdrisFileType : LanguageFileType(IdrisLanguage) {
    override fun getName(): String = "Idris"
    override fun getDescription(): String = "Idris 2 source file"
    override fun getDefaultExtension(): String = "idr"
    override fun getIcon(): Icon = IdrisIcons.FILE
}
