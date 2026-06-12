package io.github.mmhelloworld.idrisintellij.lang

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

/**
 * Literate Idris (bird-track style: code lines start with `>`), registered as
 * a dialect of [IdrisLanguage] so language extensions (annotator, docs,
 * commenter...) are inherited via the base-language chain.
 */
object IdrisLiterateLanguage : Language(IdrisLanguage, "IdrisLiterate") {
    private fun readResolve(): Any = IdrisLiterateLanguage
    override fun getDisplayName(): String = "Literate Idris"
}

object IdrisLiterateFileType : LanguageFileType(IdrisLiterateLanguage) {
    override fun getName(): String = "Literate Idris"
    override fun getDescription(): String = "Literate Idris 2 source file (bird-track)"
    override fun getDefaultExtension(): String = "lidr"
    override fun getIcon(): Icon = IdrisIcons.FILE
}
