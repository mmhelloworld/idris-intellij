package io.github.mmhelloworld.idrisintellij.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Triggers the completion popup automatically while typing an identifier or a
 * `.` (member access) in an Idris file, instead of only on explicit Ctrl+Space.
 *
 * The actual work in [io.github.mmhelloworld.idrisintellij.navigation.IdrisCompletionContributor]
 * runs off the EDT, returns early when the file has no fresh compiler state, and
 * honours cancellation, so scheduling on every keystroke is safe.
 */
class IdrisTypedHandler : TypedHandlerDelegate() {

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!file.language.isKindOf(IdrisLanguage)) return Result.CONTINUE
        if (charTyped == '.' || charTyped.isLetterOrDigit() || charTyped == '_') {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }
}
