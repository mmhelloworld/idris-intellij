package io.github.mmhelloworld.idrisintellij.navigation

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer
import io.github.mmhelloworld.idrisintellij.ide.IdrisIdeService
import io.github.mmhelloworld.idrisintellij.ide.LoadResult
import io.github.mmhelloworld.idrisintellij.lang.IdrisLanguage

/**
 * "Highlight usages in file" (Cmd+Shift+F7 / on-caret highlighting) for Idris
 * names. The protocol has no find-references, but the cached semantic highlights
 * record every occurrence with its fully qualified name, so we highlight all
 * occurrences sharing the qualified name under the caret — within this file.
 *
 * Answers only from a fresh cached load; returns null (deferring to default
 * handling) when the file isn't loaded or the caret isn't on a named symbol.
 */
class IdrisHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory {

    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile): HighlightUsagesHandlerBase<*>? {
        if (!file.language.isKindOf(IdrisLanguage)) return null
        val virtualFile = file.virtualFile ?: return null
        val load = IdrisIdeService.getInstance(file.project).cachedLoad(virtualFile) ?: return null
        val offset = editor.caretModel.offset
        val span = IdrisHighlights.spanAt(load, editor.document, offset) ?: return null
        val qualifiedName = span.qualifiedName ?: return null
        val target = file.findElementAt(offset) ?: return null
        return IdrisHighlightUsagesHandler(editor, file, load, qualifiedName, target)
    }
}

private class IdrisHighlightUsagesHandler(
    editor: Editor,
    file: PsiFile,
    private val load: LoadResult,
    private val qualifiedName: String,
    private val target: PsiElement,
) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {

    override fun getTargets(): MutableList<PsiElement> = mutableListOf(target)

    override fun selectTargets(targets: MutableList<out PsiElement>, selectionConsumer: Consumer<in MutableList<out PsiElement>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: MutableList<out PsiElement>) {
        myReadUsages.addAll(IdrisHighlights.occurrencesOf(load, myEditor.document, qualifiedName))
    }
}
