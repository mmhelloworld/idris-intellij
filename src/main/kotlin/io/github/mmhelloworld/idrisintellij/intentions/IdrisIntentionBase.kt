package io.github.mmhelloworld.idrisintellij.intentions

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.mmhelloworld.idrisintellij.ide.IdrisIdeService
import io.github.mmhelloworld.idrisintellij.lang.IdrisLanguage
import io.github.mmhelloworld.idrisintellij.lang.IdrisTokenTypes
import io.github.mmhelloworld.idrisintellij.protocol.IdeResult
import io.github.mmhelloworld.idrisintellij.protocol.SExp
import java.util.concurrent.TimeUnit

/** Everything an intention needs, captured on the EDT before going async. */
data class IntentionContext(
    val name: String,
    /** 1-based line of the caret — what the editing commands expect. */
    val line1: Int,
    val tokenStartOffset: Int,
    val tokenEndOffset: Int,
    /** Extra user input (e.g. the refine expression), when the intention prompts. */
    val argument: String? = null,
)

/**
 * Common machinery for the ide-mode editing commands: save the file, run the
 * command under a modal progress (the compiler may need to (re)load the file),
 * then apply the returned text as a document edit.
 */
abstract class IdrisIntentionBase(private val actionText: String) : IntentionAction {

    protected companion object {
        const val COMMAND_TIMEOUT_MS = 60_000L
    }

    /** Valid during [invoke] only (intentions run sequentially on the EDT). */
    protected var currentProject: Project? = null
        private set
    protected var currentFile: VirtualFile? = null
        private set

    final override fun getText(): String = "Idris: $actionText"

    final override fun getFamilyName(): String = "Idris"

    final override fun startInWriteAction(): Boolean = false

    /** Which caret token makes this intention available. */
    protected abstract fun isAvailableForToken(token: PsiElement, document: Document): Boolean

    /** The ide-mode command to send. */
    protected abstract fun command(context: IntentionContext): SExp

    /** Applies the successful result to the document (called inside a write command). */
    protected abstract fun applyEdit(document: Document, context: IntentionContext, result: IdeResult)

    /** The name argument; holes are passed without the leading `?`. */
    protected open fun nameFor(token: PsiElement): String = token.text.removePrefix("?")

    /** Override to ask the user for input; returning null cancels when [requiresArgument]. */
    protected open fun promptForArgument(context: IntentionContext): String? = null

    protected open val requiresArgument: Boolean = false

    /**
     * Whether to save + (re)load the file before the command. The `-next`
     * search commands must NOT reload: a reload resets the server's search
     * iterator (and the document deliberately differs from the loaded state
     * while candidates are being cycled).
     */
    protected open val reloadsFile: Boolean = true

    final override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file == null || !file.language.isKindOf(IdrisLanguage) || editor == null) return false
        if (file.virtualFile == null) return false
        val token = file.findElementAt(editor.caretModel.offset) ?: return false
        return isAvailableForToken(token, editor.document)
    }

    final override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val virtualFile = file.virtualFile ?: return
        val offset = editor.caretModel.offset
        val token = file.findElementAt(offset) ?: return
        val document = editor.document
        var context = IntentionContext(
            name = nameFor(token),
            line1 = document.getLineNumber(offset) + 1,
            tokenStartOffset = token.textRange.startOffset,
            tokenEndOffset = token.textRange.endOffset,
        )

        currentProject = project
        currentFile = virtualFile
        try {
            val argument = promptForArgument(context)
            if (requiresArgument && argument == null) return
            context = context.copy(argument = argument)

            if (reloadsFile) {
                FileDocumentManager.getInstance().saveDocument(document)
            }

            val service = IdrisIdeService.getInstance(project)
            var result: IdeResult? = null
            var failure: Exception? = null
            val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    try {
                        val future =
                            if (reloadsFile) service.request(virtualFile, command(context), COMMAND_TIMEOUT_MS)
                            else service.rawRequest(service.rootFor(virtualFile), command(context), COMMAND_TIMEOUT_MS)
                        result = future.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        failure = e
                    }
                },
                text, true, project,
            )
            if (!completed) return

            val ideResult = result
            if (ideResult == null || !ideResult.ok) {
                val message = ideResult?.errorMessage
                    ?: failure?.cause?.message
                    ?: failure?.message
                    ?: "Idris command failed"
                HintManager.getInstance().showErrorHint(editor, message)
                return
            }
            val finalContext = context
            WriteCommandAction.runWriteCommandAction(project, text, null, {
                applyEdit(document, finalContext, ideResult)
            }, file)
        } finally {
            currentProject = null
            currentFile = null
        }
    }

    /** Remembers where a search result landed so the `-next` command can swap it. */
    protected fun recordSearchEdit(
        document: Document,
        context: IntentionContext,
        startOffset: Int,
        endOffset: Int,
        nextCommand: SExp,
    ) {
        val project = currentProject ?: return
        val file = currentFile ?: return
        val service = IdrisIdeService.getInstance(project)
        service.lastSearchEdit?.marker?.dispose()
        service.lastSearchEdit = IdrisIdeService.LastSearchEdit(
            file.path,
            nextCommand,
            document.createRangeMarker(
                startOffset.coerceIn(0, document.textLength),
                endOffset.coerceIn(startOffset, document.textLength),
            ),
        )
    }

    protected fun replaceLine(document: Document, line1: Int, newText: String) {
        val line0 = line1 - 1
        if (line0 >= document.lineCount) return
        document.replaceString(
            document.getLineStartOffset(line0),
            document.getLineEndOffset(line0),
            newText.removeSuffix("\n"),
        )
    }

    protected fun insertAfterLine(document: Document, line1: Int, newText: String) {
        val line0 = line1 - 1
        if (line0 >= document.lineCount) return
        document.insertString(document.getLineEndOffset(line0), "\n" + newText.removeSuffix("\n"))
    }

    /** True when the token is a hole `?name`. */
    protected fun isHole(token: PsiElement): Boolean =
        token.node?.elementType == IdrisTokenTypes.HOLE

    protected fun isIdentifier(token: PsiElement): Boolean =
        token.node?.elementType == IdrisTokenTypes.IDENTIFIER

    /** Heuristic: the caret line contains a top-level `:` (a type signature). */
    protected fun lineLooksLikeTypeSignature(document: Document, offset: Int): Boolean {
        val line = document.getLineNumber(offset)
        val text = document.charsSequence
            .subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString()
        return text.contains(":") && !text.trimStart().startsWith("--")
    }
}
