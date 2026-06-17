package io.github.mmhelloworld.idrisintellij.navigation

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import io.github.mmhelloworld.idrisintellij.ide.IdrisIdeService
import io.github.mmhelloworld.idrisintellij.lang.IdrisLanguage
import io.github.mmhelloworld.idrisintellij.lang.IdrisTokenTypes
import io.github.mmhelloworld.idrisintellij.protocol.IdeCommands
import io.github.mmhelloworld.idrisintellij.protocol.IdeResult
import io.github.mmhelloworld.idrisintellij.protocol.ResultDecoder
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Identifier and member completion.
 *
 * The ide-mode protocol offers only `:repl-completions` (a name-prefix lookup
 * over names in scope) — there is no type-directed member query, so everything
 * here is built on that one source plus two cheap, no-recompile signals: the
 * cached semantic highlights (each name's decoration kind, used for icons/kind
 * labels and for surfacing locally-bound variables) and bare-name `:type-of`.
 *
 * What it does:
 *  - enriches every candidate with a kind icon + kind label, and (on EXPLICIT
 *    invoke only) an inline `: type` signature via `:type-of`;
 *  - handles dotted prefixes (`Data.List.fo`, `r.fi`) via qualified completion,
 *    with a best-effort record-projection fallback;
 *  - supplements with in-scope local (bound) names that `:repl-completions`
 *    does not return.
 *
 * Only answers when the file's compiler state is already loaded and fresh;
 * completion never triggers a compile.
 */
class IdrisCompletionContributor : CompletionContributor() {

    private companion object {
        const val REQUEST_TIMEOUT_MS = 2_500L
        const val POLL_MS = 25L

        /** Max candidates enriched with an inline `:type-of` signature (explicit invoke). */
        const val TYPE_LOOKUP_LIMIT = 15

        /** Shared wall-clock budget for the per-candidate `:type-of` round-trips. */
        const val TYPE_BUDGET_MS = 2_000L

        fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '\''
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!file.language.isKindOf(IdrisLanguage)) return
        val virtualFile = file.virtualFile ?: return

        val tokenType = parameters.position.node?.elementType
        if (tokenType != IdrisTokenTypes.IDENTIFIER && tokenType != IdrisTokenTypes.HOLE) return

        // Reassemble the dotted chain from the original document (the lexer splits
        // `Data.List.fo` into IDENTIFIER (OPERATOR "." IDENTIFIER)*, and the
        // completion dummy never reaches the original document).
        val text = parameters.editor.document.charsSequence
        val full = assemblePrefix(text, parameters.offset)
        if (full.isEmpty()) return
        val lastDot = full.lastIndexOf('.')
        val receiver = if (lastDot >= 0) full.substring(0, lastDot) else null
        val tail = if (lastDot >= 0) full.substring(lastDot + 1) else full

        val service = IdrisIdeService.getInstance(file.project)
        val load = service.cachedLoad(virtualFile) ?: return
        val root = service.rootFor(virtualFile)
        val explicit = parameters.invocationCount >= 1

        // name -> decoration, for icons/kind labels and the local-name pass.
        val decorByName = HashMap<String, String>()
        for (span in load.highlights) {
            val name = span.name ?: continue
            decorByName.putIfAbsent(name, span.decor)
        }
        fun decorOf(name: String): String? =
            decorByName[name] ?: decorByName[name.substringAfterLast('.')]

        // The text the candidate replaces: the whole dotted chain for member
        // completion, otherwise the bare prefix.
        val matcher = result.withPrefixMatcher(if (receiver != null) full else tail)
        val added = HashSet<String>()
        val typeBudgetEnd = System.currentTimeMillis() + TYPE_BUDGET_MS
        var typeLookups = 0

        fun addCandidate(name: String) {
            if (!added.add(name)) return
            val kind = IdrisCompletionIcons.forDecoration(decorOf(name))
            var builder = LookupElementBuilder.create(name).withIcon(kind.icon)
            if (kind.label.isNotEmpty()) builder = builder.withTypeText(kind.label, true)
            // Inline signature only on explicit invoke, bounded in count and time,
            // so autopopup stays responsive (the server is strictly sequential).
            if (explicit && typeLookups < TYPE_LOOKUP_LIMIT && System.currentTimeMillis() < typeBudgetEnd) {
                typeLookups++
                signatureOf(service, root, name, typeBudgetEnd)?.let { builder = builder.withTailText("  $it", true) }
            }
            matcher.addElement(builder)
        }

        // 1. Primary: prefix (or qualified) completion via the compiler.
        val primary = replCompletions(service, root, full)
        primary.forEach(::addCandidate)

        if (receiver == null) {
            // 2. In-scope local names the compiler does not return (bound vars,
            //    and this file's own top-level definitions), matched by prefix.
            //    NB: highlights are whole-file occurrences, not caret-scoped, so a
            //    binder from another clause may appear — acceptable over-suggestion.
            localNameMatches(decorByName, tail).forEach(::addCandidate)
        } else if (primary.isEmpty() && !receiver.contains('.')) {
            // 3. Record-projection heuristic (best-effort; the protocol cannot
            //    enumerate a record's fields). Resolve the receiver's head type
            //    and keep global candidates whose signature mentions it.
            recordProjections(service, root, receiver, tail, typeBudgetEnd).forEach(::addCandidate)
        }
    }

    /** Walks left over identifier characters and dots to rebuild the dotted prefix. */
    private fun assemblePrefix(text: CharSequence, offset: Int): String {
        var i = offset.coerceIn(0, text.length)
        while (i > 0) {
            val c = text[i - 1]
            if (isIdentPart(c) || c == '.') i-- else break
        }
        return text.subSequence(i, offset.coerceIn(0, text.length)).toString()
    }

    private fun replCompletions(service: IdrisIdeService, root: Path, prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val reply = awaitReply(
            service.rawRequest(root, IdeCommands.replCompletions(prefix), REQUEST_TIMEOUT_MS),
            System.currentTimeMillis() + REQUEST_TIMEOUT_MS,
        ) ?: return emptyList()
        if (!reply.ok) return emptyList()
        return ResultDecoder.parseCompletions(reply.payload)
    }

    /** Distinct local (bound) names plus this file's own definitions matching [prefix]. */
    private fun localNameMatches(decorByName: Map<String, String>, prefix: String): List<String> =
        decorByName.entries
            .filter { (name, decor) ->
                name.startsWith(prefix) &&
                    (decor == "bound" || decor == "function" || decor == "data" || decor == "type")
            }
            .map { it.key }

    /** `name : T` → the grayed ` : T` tail; null if the lookup fails. */
    private fun signatureOf(service: IdrisIdeService, root: Path, name: String, deadlineMs: Long): String? {
        val reply = awaitReply(service.rawRequest(root, IdeCommands.typeOf(name), REQUEST_TIMEOUT_MS), deadlineMs)
            ?: return null
        if (!reply.ok) return null
        val type = reply.text?.substringAfter(" : ", "")?.trim().orEmpty()
        return if (type.isEmpty()) null else ": $type"
    }

    /**
     * Best-effort record/interface member suggestions: resolve the receiver's
     * head type constructor, then keep prefix candidates whose own signature
     * mentions it (i.e. plausible projections/methods on that type).
     */
    private fun recordProjections(
        service: IdrisIdeService,
        root: Path,
        receiver: String,
        tail: String,
        deadlineMs: Long,
    ): List<String> {
        val receiverType = awaitReply(service.rawRequest(root, IdeCommands.typeOf(receiver), REQUEST_TIMEOUT_MS), deadlineMs)
            ?.takeIf { it.ok }?.text ?: return emptyList()
        val head = headTypeConstructor(receiverType) ?: return emptyList()
        val headWord = Regex("\\b${Regex.escape(head)}\\b")
        val candidates = replCompletions(service, root, tail)
        val matches = ArrayList<String>()
        for (candidate in candidates) {
            if (System.currentTimeMillis() >= deadlineMs || matches.size >= TYPE_LOOKUP_LIMIT) break
            val candType = awaitReply(service.rawRequest(root, IdeCommands.typeOf(candidate), REQUEST_TIMEOUT_MS), deadlineMs)
                ?.takeIf { it.ok }?.text ?: continue
            if (headWord.containsMatchIn(candType)) matches.add(candidate)
        }
        return matches
    }

    /** First uppercase-initial token of the type (its outermost type constructor). */
    private fun headTypeConstructor(typeText: String): String? {
        val rhs = typeText.substringAfter(" : ", "").ifEmpty { return null }
        return Regex("[A-Za-z_][A-Za-z0-9_.']*").findAll(rhs)
            .map { it.value }
            .firstOrNull { it.firstOrNull()?.isUpperCase() == true }
            ?.substringAfterLast('.')
    }

    /** Polls [future] off the EDT until done or [deadlineMs]; null on timeout/error. */
    private fun awaitReply(future: CompletableFuture<IdeResult>, deadlineMs: Long): IdeResult? {
        while (!future.isDone) {
            ProgressManager.checkCanceled()
            if (System.currentTimeMillis() > deadlineMs) return null
            Thread.sleep(POLL_MS)
        }
        return try {
            future.get()
        } catch (e: Exception) {
            null
        }
    }
}
