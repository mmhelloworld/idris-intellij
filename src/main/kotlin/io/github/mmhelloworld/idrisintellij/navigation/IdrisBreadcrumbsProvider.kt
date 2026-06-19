package io.github.mmhelloworld.idrisintellij.navigation

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import io.github.mmhelloworld.idrisintellij.lang.IdrisDecl
import io.github.mmhelloworld.idrisintellij.lang.IdrisDeclarationScanner
import io.github.mmhelloworld.idrisintellij.lang.IdrisLanguage

/**
 * Breadcrumbs showing the enclosing declaration (and, on a constructor / field /
 * method, its member). The PSI tree is flat, so instead of walking real PSI
 * parents we drive [getParent]/[acceptElement] from [IdrisDeclarationScanner]:
 * a leaf's parent is the name-leaf of its innermost enclosing declaration; a
 * member's parent is its top-level declaration; a top-level declaration has no
 * parent (the walk stops).
 */
class IdrisBreadcrumbsProvider : BreadcrumbsProvider {

    override fun getLanguages(): Array<Language> = arrayOf(IdrisLanguage)

    override fun acceptElement(element: PsiElement): Boolean {
        val offset = element.textRange.startOffset
        return declsFor(element).any { decl ->
            decl.nameRange.startOffset == offset || decl.children.any { it.nameRange.startOffset == offset }
        }
    }

    override fun getElementInfo(element: PsiElement): String {
        val offset = element.textRange.startOffset
        for (decl in declsFor(element)) {
            if (decl.nameRange.startOffset == offset) return decl.name
            decl.children.firstOrNull { it.nameRange.startOffset == offset }?.let { return it.name }
        }
        return element.text
    }

    override fun getParent(element: PsiElement): PsiElement? {
        val file = element.containingFile ?: return null
        val decls = declsFor(element)
        val offset = element.textRange.startOffset
        val top = decls.firstOrNull { it.range.containsOffset(offset) } ?: return null
        val child = top.children.firstOrNull { it.range.containsOffset(offset) }

        // `element` is itself a name-leaf → step up to the enclosing declaration.
        if (element.textRange.startOffset == top.nameRange.startOffset) return null
        if (child != null && element.textRange.startOffset == child.nameRange.startOffset) {
            return file.findElementAt(top.nameRange.startOffset)
        }
        // Arbitrary leaf in the body → its innermost enclosing declaration name.
        val innermost = child ?: top
        return file.findElementAt(innermost.nameRange.startOffset)
    }

    private fun declsFor(element: PsiElement): List<IdrisDecl> {
        val file = element.containingFile ?: return emptyList()
        if (!file.language.isKindOf(IdrisLanguage)) return emptyList()
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(IdrisDeclarationScanner.scan(file.text), file)
        }
    }
}
