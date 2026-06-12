package io.github.mmhelloworld.idris.intellij.navigation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.mmhelloworld.idris.intellij.ide.IdrisIdeService
import io.github.mmhelloworld.idris.intellij.lang.IdrisLanguage
import io.github.mmhelloworld.idris.intellij.lang.IdrisTokenTypes
import io.github.mmhelloworld.idris.intellij.protocol.IdeCommands
import java.util.concurrent.TimeUnit

/**
 * Quick documentation backed by `:type-of` (plus `:docs-for` when the semantic
 * cache knows the fully qualified name).
 *
 * Deliberately conservative about blocking: documentation is only computed when
 * the file's load result is already cached and fresh — we never trigger a
 * recompile from inside a documentation request (which runs under a read action).
 */
class IdrisDocumentationProvider : AbstractDocumentationProvider() {

    private companion object {
        const val REQUEST_TIMEOUT_MS = 3000L
    }

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (file.language != IdrisLanguage) return null
        val element = file.findElementAt(targetOffset) ?: return null
        return when (element.node?.elementType) {
            IdrisTokenTypes.IDENTIFIER, IdrisTokenTypes.OPERATOR, IdrisTokenTypes.HOLE -> element
            else -> null
        }
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element ?: return null
        val file = target.containingFile ?: return null
        if (file.language != IdrisLanguage) return null
        val virtualFile = file.virtualFile ?: return null
        val project = file.project
        val service = IdrisIdeService.getInstance(project)

        // Only answer from an already-loaded compiler state.
        val load = service.cachedLoad(virtualFile) ?: return null

        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = target.textOffset
        val line0 = document.getLineNumber(offset)
        val col0 = offset - document.getLineStartOffset(line0)
        val name = target.text.removePrefix("?")

        val typeResult = try {
            service.rawRequest(service.rootFor(virtualFile), IdeCommands.typeOfAt(name, line0 + 1, col0), REQUEST_TIMEOUT_MS)
                .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            return null
        }
        if (!typeResult.ok) return null
        val typeText = typeResult.text ?: return null

        val builder = StringBuilder()
        builder.append("<pre><code>").append(escapeHtml(typeText)).append("</code></pre>")

        // If the semantic cache identifies the symbol, append its docs.
        val qualified = load.highlights.firstOrNull { span ->
            span.qualifiedName != null &&
                span.startLine == line0 && offset >= document.getLineStartOffset(line0) + span.startCol &&
                offset < document.getLineStartOffset(line0) + span.endCol
        }?.qualifiedName
        if (qualified != null) {
            val docsResult = try {
                service.rawRequest(service.rootFor(virtualFile), IdeCommands.docsFor(qualified), REQUEST_TIMEOUT_MS)
                    .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                null
            }
            val docsText = docsResult?.takeIf { it.ok }?.text
            if (!docsText.isNullOrBlank() && docsText.trim() != typeText.trim()) {
                builder.append("<pre>").append(escapeHtml(docsText)).append("</pre>")
            }
        }
        return builder.toString()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
