package io.github.mmhelloworld.idrisintellij.navigation

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.mmhelloworld.idrisintellij.lang.IdrisDecl
import io.github.mmhelloworld.idrisintellij.lang.IdrisDeclIcons
import io.github.mmhelloworld.idrisintellij.lang.IdrisDeclKind
import io.github.mmhelloworld.idrisintellij.lang.IdrisDeclarationScanner
import io.github.mmhelloworld.idrisintellij.lang.IdrisLanguage
import io.github.mmhelloworld.idrisintellij.lang.IdrisLiterateLanguage
import javax.swing.Icon

/** File outline (modules, data/record/interface + members, functions) from the scanner. */
class IdrisStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        // .lidr (bird-track) columns differ; the scanner is for plain .idr only.
        if (!psiFile.language.isKindOf(IdrisLanguage) || psiFile.language.isKindOf(IdrisLiterateLanguage)) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                IdrisStructureViewModel(psiFile)
        }
    }
}

private class IdrisStructureViewModel(psiFile: PsiFile) :
    StructureViewModelBase(psiFile, IdrisStructureViewElement.forFile(psiFile)),
    StructureViewModel.ElementInfoProvider {

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        (element as? IdrisStructureViewElement)?.isLeaf ?: false
}

private class IdrisStructureViewElement private constructor(
    private val file: PsiFile,
    private val decl: IdrisDecl?,
) : StructureViewTreeElement {

    val isLeaf: Boolean get() = decl != null && decl.children.isEmpty()

    private val navElement: PsiElement =
        decl?.let { file.findElementAt(it.nameRange.startOffset) } ?: file

    override fun getValue(): Any = navElement

    override fun navigate(requestFocus: Boolean) {
        (navElement as? com.intellij.pom.Navigatable)?.takeIf { it.canNavigate() }?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (navElement as? com.intellij.pom.Navigatable)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = decl?.name ?: file.name
        override fun getIcon(unused: Boolean): Icon? = decl?.let { IdrisDeclIcons.forKind(it.kind) }
    }

    override fun getChildren(): Array<TreeElement> {
        // Imports add a lot of noise to the outline and aren't declarations you
        // navigate within; the module header still shows the file's identity.
        val childDecls = (decl?.children ?: IdrisDeclarationScanner.scan(file.text))
            .filter { it.kind != IdrisDeclKind.IMPORT }
        return childDecls.map { IdrisStructureViewElement(file, it) }.toTypedArray()
    }

    companion object {
        fun forFile(file: PsiFile) = IdrisStructureViewElement(file, null)
    }
}
