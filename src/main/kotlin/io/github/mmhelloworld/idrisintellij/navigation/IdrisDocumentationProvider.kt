package io.github.mmhelloworld.idrisintellij.navigation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.mmhelloworld.idrisintellij.ide.IdrisIdeService
import io.github.mmhelloworld.idrisintellij.lang.IdrisLanguage
import io.github.mmhelloworld.idrisintellij.lang.IdrisTokenTypes
import io.github.mmhelloworld.idrisintellij.protocol.IdeCommands
import io.github.mmhelloworld.idrisintellij.protocol.SExp
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Quick documentation backed by `:type-of` and `:docs-for`.
 *
 * `:type-of` at the caret is position-resolved, so it pins down the ONE symbol
 * under the cursor and returns `Qualified.Name : Type`. `:docs-for` only accepts
 * a BARE name and returns one block per same-named definition across all loaded
 * modules, so we keep just the block whose header matches the resolved name —
 * otherwise hovering `length` would dump every `length` in scope.
 *
 * Deliberately conservative about blocking: documentation is only computed when
 * the file's load result is already cached and fresh — we never trigger a
 * recompile from inside a documentation request (which runs under a read action).
 */
class IdrisDocumentationProvider : AbstractDocumentationProvider() {

    companion object {
        private const val REQUEST_TIMEOUT_MS = 3000L

        /**
         * `:docs-for "bare"` output is a sequence of blocks, each headed by a
         * non-indented `Qualified.Name : Type` line followed by indented detail
         * lines. Returns the block whose header name equals [qualifiedName],
         * trimmed of trailing blank lines, or null when none matches.
         */
        internal fun selectDocBlock(docs: String, qualifiedName: String): String? {
            val blocks = mutableListOf<MutableList<String>>()
            for (line in docs.lines()) {
                if (line.isNotEmpty() && !line[0].isWhitespace()) {
                    blocks.add(mutableListOf(line))
                } else {
                    blocks.lastOrNull()?.add(line)
                }
            }
            return blocks
                .firstOrNull { it.first().substringBefore(" : ").trim() == qualifiedName }
                ?.joinToString("\n")
                ?.trimEnd()
        }

        /** Metadata labels idris emits after the doc comment; each starts a section. */
        private val SECTION_LABELS = listOf("Totality:", "Visibility:")

        private fun startsSection(line: String): Boolean {
            val trimmed = line.trimStart()
            return SECTION_LABELS.any { trimmed.startsWith(it) }
        }

        /**
         * Separates a doc block's sections with a blank line: the type signature
         * (header line), the documentation, and each metadata line (`Totality:`,
         * `Visibility:`). A boundary that would double an existing blank line is
         * collapsed, so blocks without a doc comment don't gain a stray gap.
         */
        internal fun spaceSections(block: String): String {
            val lines = block.lines()
            val out = ArrayList<String>(lines.size + SECTION_LABELS.size + 1)
            for ((index, line) in lines.withIndex()) {
                // index == 1 is the first line after the signature: the doc (or,
                // when undocumented, the first metadata line).
                val boundary = index == 1 || startsSection(line)
                if (boundary && out.lastOrNull()?.isNotBlank() == true) out.add("")
                out.add(line)
            }
            return out.joinToString("\n")
        }
    }

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (!file.language.isKindOf(IdrisLanguage)) return null
        val element = file.findElementAt(targetOffset) ?: return null
        return when (element.node?.elementType) {
            IdrisTokenTypes.IDENTIFIER, IdrisTokenTypes.OPERATOR, IdrisTokenTypes.HOLE -> element
            else -> null
        }
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element ?: return null
        val file = target.containingFile ?: return null
        if (!file.language.isKindOf(IdrisLanguage)) return null
        val virtualFile = file.virtualFile ?: return null
        val project = file.project
        val service = IdrisIdeService.getInstance(project)

        // Only answer from an already-loaded compiler state; never recompile here.
        service.cachedLoad(virtualFile) ?: return null
        val root = service.rootFor(virtualFile)

        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = target.textOffset
        val line0 = document.getLineNumber(offset)
        val col0 = offset - document.getLineStartOffset(line0)
        // Drop any module qualifier the user typed; `:type-of` resolves the
        // actual symbol from the caret position, not this name.
        val cursorName = target.text.removePrefix("?").substringAfterLast('.')

        // Position-resolved: pins the one symbol under the cursor and gives us
        // its display-qualified name ("Prelude.List.length : List a -> Nat").
        val typeText = request(service, root, IdeCommands.typeOfAt(cursorName, line0 + 1, col0))
            ?.takeIf { it.isNotBlank() } ?: return null
        val qualifiedName = typeText.substringBefore(" : ").trim()

        // `:docs-for` accepts only the bare name and returns a block per
        // same-named definition; keep just the one for our resolved symbol.
        val bareName = qualifiedName.substringAfterLast('.')
        val docBlock = request(service, root, IdeCommands.docsFor(bareName))
            ?.let { selectDocBlock(it, qualifiedName) }

        // The doc block already includes the signature line; fall back to the
        // bare type when there are no docs (holes, locals, no matching block).
        return "<pre>${escapeHtml(spaceSections(docBlock ?: typeText))}</pre>"
    }

    /** Runs one IDE request, returning its text payload or null on error/failure. */
    private fun request(service: IdrisIdeService, root: Path, command: SExp): String? =
        try {
            service.rawRequest(root, command, REQUEST_TIMEOUT_MS)
                .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .takeIf { it.ok }
                ?.text
        } catch (e: Exception) {
            null
        }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
