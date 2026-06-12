package io.github.mmhelloworld.idris.intellij.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import io.github.mmhelloworld.idris.intellij.ide.IdrisIdeService
import io.github.mmhelloworld.idris.intellij.lang.IdrisLanguage

/**
 * Go-to-definition: the semantic-highlight cache identifies the fully qualified
 * name under the caret (highlight spans are occurrences); `(:name-at fqn)`
 * resolves it to definition locations with absolute paths and 0-based positions.
 */
class IdrisGotoDeclarationHandler : GotoDeclarationHandler {

    private companion object {
        const val NAME_AT_TIMEOUT_MS = 3000L
    }

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val file = element.containingFile ?: return null
        if (!file.language.isKindOf(IdrisLanguage)) return null
        val virtualFile = file.virtualFile ?: return null
        val project = file.project
        val service = IdrisIdeService.getInstance(project)

        // Only navigate from a fresh compiler state; never trigger a recompile here.
        val load = service.cachedLoad(virtualFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null

        val line0 = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line0)
        val col0 = offset - lineStart
        val span = load.highlights.firstOrNull { span ->
            span.name != null &&
                span.startLine == line0 && col0 >= span.startCol && col0 < span.endCol
        } ?: return null
        val bareName = span.name ?: return null

        val locations = service.definitionsFor(
            service.rootFor(virtualFile), bareName, span.qualifiedName, NAME_AT_TIMEOUT_MS)
            ?: return null

        val psiManager = PsiManager.getInstance(project)
        val targets = locations.mapNotNull { location ->
            val targetVFile = LocalFileSystem.getInstance().findFileByPath(location.span.file)
                ?: return@mapNotNull null
            val psiFile = psiManager.findFile(targetVFile) ?: return@mapNotNull null
            val targetDocument = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@mapNotNull null
            if (location.span.startLine >= targetDocument.lineCount) return@mapNotNull null
            val targetOffset = targetDocument.getLineStartOffset(location.span.startLine) + location.span.startCol
            psiFile.findElementAt(targetOffset.coerceIn(0, maxOf(0, targetDocument.textLength - 1)))
        }
        return if (targets.isEmpty()) null else targets.toTypedArray()
    }
}
