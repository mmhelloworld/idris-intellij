package io.github.mmhelloworld.idrisintellij.navigation

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.mmhelloworld.idrisintellij.ide.IdrisIdeService
import io.github.mmhelloworld.idrisintellij.lang.IdrisLanguage
import io.github.mmhelloworld.idrisintellij.lang.IdrisTokenTypes
import io.github.mmhelloworld.idrisintellij.protocol.CatalogMember
import io.github.mmhelloworld.idrisintellij.protocol.IdeCommands
import io.github.mmhelloworld.idrisintellij.protocol.IdeResult
import io.github.mmhelloworld.idrisintellij.protocol.ResultDecoder
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * (classpath   internalClass) -> catalog members. The catalog is classpath-stable, so this
     * is cached for the session; the key includes the classpath, so a dependency change (which
     * re-resolves the module classpath) yields a fresh key. Successes and definitive not-found
     * (ClassNotFound) are cached; transient timeouts are not, so they retry.
     */
    private val catalogCache = ConcurrentHashMap<String, List<CatalogMember>>()

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
        // Fall back to the last load that had highlights: while you are mid-edit the file is dirty
        // (cachedLoad is stale) or transiently unparseable (latest load has no highlights), but the
        // last good names/namespaces are still the right basis for resolving the completion receiver.
        val load = service.cachedLoad(virtualFile) ?: service.lastGoodLoad(virtualFile) ?: return
        val root = service.rootFor(virtualFile)
        val explicit = parameters.invocationCount >= 1

        // name -> decoration, for icons/kind labels and the local-name pass.
        val decorByName = HashMap<String, String>()
        // name -> namespace, used to resolve a Java-FFI marker receiver to its internal class name.
        val namespaceByName = HashMap<String, String>()
        for (span in load.highlights) {
            val name = span.name ?: continue
            decorByName.putIfAbsent(name, span.decor)
            span.namespace?.takeIf { it.isNotEmpty() }?.let { namespaceByName.putIfAbsent(name, it) }
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

        // A Java-FFI member from the `:jvm-ffi-list` catalog: insert `Receiver.name`, and show the
        // rendered signature inline (no extra `:type-of` round-trip — the catalog already carries it).
        // A member not yet emitted into the binding module (`isNew`) gets an insert handler that
        // (re)generates it via `--jvm-ffi-import` on accept, so the reference resolves.
        fun addCatalogMember(member: CatalogMember, isNew: Boolean, internalClass: String) {
            val text = "$receiver.${member.name}"
            if (!added.add(text)) return
            var builder = LookupElementBuilder.create(text)
                .withIcon(IdrisCompletionIcons.forDecoration("function").icon)
                .withTypeText(if (isNew) "java (import)" else "java", true)
                .withTailText("  ${member.signature}", true)
            if (isNew) builder = builder.withInsertHandler(JvmFfiImportInsertHandler(internalClass))
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
        } else {
            // 3. Java-FFI member discovery: if the receiver resolves to a generated Java marker,
            //    offer the class's FULL callable surface — including members not yet emitted by
            //    `--jvm-ffi-import` (the demand-driven generator only emits referenced members).
            //    This is what breaks the chicken-and-egg: you can see a method before referencing it.
            val internalClass = resolveInternalClass(receiver, namespaceByName)
            if (internalClass != null) {
                val members = jvmFfiMembers(service, root, file.project, virtualFile, internalClass)
                if (members.isNotEmpty()) {
                    // Members already emitted into the binding module (in scope) vs new ones whose
                    // acceptance must trigger (re)generation. An unqualified-namespace completion
                    // enumerates the generated members regardless of the current tail.
                    val generated = replCompletions(service, root, "$receiver.")
                        .mapTo(HashSet()) { it.substringAfterLast('.') }
                    members.forEach { addCatalogMember(it, it.name !in generated, internalClass) }
                }
            }
            if (primary.isEmpty() && !receiver.contains('.')) {
                // 4. Record-projection heuristic (best-effort; the protocol cannot enumerate a
                //    record's fields). Resolve the receiver's head type and keep global candidates
                //    whose signature mentions it.
                recordProjections(service, root, receiver, tail, typeBudgetEnd).forEach(::addCandidate)
            }
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

    /**
     * Full callable surface of [internalClass] (via `:jvm-ffi-list`), cached per (classpath, class).
     */
    private fun jvmFfiMembers(
        service: IdrisIdeService,
        root: Path,
        project: Project,
        file: VirtualFile,
        internalClass: String,
    ): List<CatalogMember> {
        val classpath = IdrisJvmFfi.moduleClasspath(project, file)
        val key = "$classpath $internalClass"
        catalogCache[key]?.let { return it }
        // A timeout is transient (don't cache); a success or a definitive not-found is cached.
        val reply = awaitReply(
            service.rawRequest(root, IdeCommands.jvmFfiList(classpath, internalClass), REQUEST_TIMEOUT_MS),
            System.currentTimeMillis() + REQUEST_TIMEOUT_MS,
        ) ?: return emptyList()
        val members = if (reply.ok) ResultDecoder.parseCatalog(reply.text.orEmpty()) else emptyList()
        catalogCache[key] = members
        return members
    }

    /**
     * Map a marker receiver to a JVM internal class name, inverting the generator's package naming
     * (each segment capitalised): `Java.Util` + `ArrayList` -> `java/util/ArrayList`. The namespace
     * comes from the receiver itself when qualified (`Java.Util.ArrayList`), else from the cached
     * highlights. Returns null when the receiver's namespace is unknown (not a resolved marker).
     */
    private fun resolveInternalClass(receiver: String, namespaceByName: Map<String, String>): String? {
        val simple = receiver.substringAfterLast('.')
        val namespace = if (receiver.contains('.')) {
            receiver.substringBeforeLast('.')
        } else {
            namespaceByName[simple] ?: return null
        }
        val pkg = namespace.split('.').filter { it.isNotEmpty() }
            .joinToString("/") { it.replaceFirstChar(Char::lowercaseChar) }
        return if (pkg.isEmpty()) null else "$pkg/$simple"
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
