package io.github.mmhelloworld.idris.intellij.navigation

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import io.github.mmhelloworld.idris.intellij.ide.IdrisIdeService
import io.github.mmhelloworld.idris.intellij.lang.IdrisIcons
import io.github.mmhelloworld.idris.intellij.lang.IdrisLanguage
import io.github.mmhelloworld.idris.intellij.lang.IdrisTokenTypes
import io.github.mmhelloworld.idris.intellij.protocol.IdeCommands
import io.github.mmhelloworld.idris.intellij.protocol.ResultDecoder

/**
 * Identifier completion via `:repl-completions` (name-prefix based, not
 * context-aware — that is all the protocol offers). Only answers when the
 * file's compiler state is already loaded and fresh; completion never
 * triggers a compile.
 */
class IdrisCompletionContributor : CompletionContributor() {

    private companion object {
        const val REQUEST_TIMEOUT_MS = 2_500L
        const val POLL_MS = 25L
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!file.language.isKindOf(IdrisLanguage)) return
        val virtualFile = file.virtualFile ?: return

        val position = parameters.position
        val tokenType = position.node?.elementType
        if (tokenType != IdrisTokenTypes.IDENTIFIER && tokenType != IdrisTokenTypes.HOLE) return

        val prefix = position.text
            .substring(0, (parameters.offset - position.textRange.startOffset).coerceIn(0, position.textLength))
            .removePrefix("?")
        if (prefix.isEmpty()) return

        val service = IdrisIdeService.getInstance(file.project)
        if (service.cachedLoad(virtualFile) == null) return

        val future = service.rawRequest(
            service.rootFor(virtualFile), IdeCommands.replCompletions(prefix), REQUEST_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        while (!future.isDone) {
            ProgressManager.checkCanceled()
            if (System.currentTimeMillis() > deadline) return
            Thread.sleep(POLL_MS)
        }
        val response = try {
            future.get()
        } catch (e: Exception) {
            return
        }
        if (!response.ok) return

        val matcher = result.withPrefixMatcher(prefix)
        for (completion in ResultDecoder.parseCompletions(response.payload)) {
            matcher.addElement(
                LookupElementBuilder.create(completion).withIcon(IdrisIcons.FILE))
        }
    }
}
