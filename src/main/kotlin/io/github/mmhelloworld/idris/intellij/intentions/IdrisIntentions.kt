package io.github.mmhelloworld.idris.intellij.intentions

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import io.github.mmhelloworld.idris.intellij.protocol.IdeCommands
import io.github.mmhelloworld.idris.intellij.protocol.IdeResult
import io.github.mmhelloworld.idris.intellij.protocol.ResultDecoder
import io.github.mmhelloworld.idris.intellij.protocol.SExp

/** `:case-split` — replaces the clause line with one clause per constructor. */
class CaseSplitIntention : IdrisIntentionBase("Case split") {
    override fun isAvailableForToken(token: PsiElement, document: Document): Boolean =
        isIdentifier(token) && !lineLooksLikeTypeSignature(document, token.textRange.startOffset)

    override fun command(context: IntentionContext): SExp =
        IdeCommands.caseSplit(context.line1, context.name)

    override fun applyEdit(document: Document, context: IntentionContext, result: IdeResult) {
        replaceLine(document, context.line1, result.text ?: return)
    }
}

/** `:add-clause` — inserts an initial clause after the type signature line. */
class AddClauseIntention : IdrisIntentionBase("Add clause") {
    override fun isAvailableForToken(token: PsiElement, document: Document): Boolean =
        isIdentifier(token) && lineLooksLikeTypeSignature(document, token.textRange.startOffset)

    override fun command(context: IntentionContext): SExp =
        IdeCommands.addClause(context.line1, context.name)

    override fun applyEdit(document: Document, context: IntentionContext, result: IdeResult) {
        insertAfterLine(document, context.line1, result.text ?: return)
    }
}

/** `:proof-search` — replaces the hole with a found expression. */
class ProofSearchIntention : IdrisIntentionBase("Proof search") {
    override fun isAvailableForToken(token: PsiElement, document: Document): Boolean = isHole(token)

    override fun command(context: IntentionContext): SExp =
        IdeCommands.proofSearch(context.line1, context.name)

    override fun applyEdit(document: Document, context: IntentionContext, result: IdeResult) {
        val expression = result.text ?: return
        document.replaceString(context.tokenStartOffset, context.tokenEndOffset, expression)
    }
}

/** `:generate-def` — inserts a complete definition after the signature line. */
class GenerateDefIntention : IdrisIntentionBase("Generate definition") {
    override fun isAvailableForToken(token: PsiElement, document: Document): Boolean =
        isIdentifier(token) && lineLooksLikeTypeSignature(document, token.textRange.startOffset)

    override fun command(context: IntentionContext): SExp =
        IdeCommands.generateDef(context.line1, context.name)

    override fun applyEdit(document: Document, context: IntentionContext, result: IdeResult) {
        insertAfterLine(document, context.line1, result.text ?: return)
    }
}

/**
 * `:make-lemma` — replaces the hole with an application of a new lemma and
 * inserts the lemma's type signature above the enclosing top-level declaration.
 */
class MakeLemmaIntention : IdrisIntentionBase("Make lemma") {
    override fun isAvailableForToken(token: PsiElement, document: Document): Boolean = isHole(token)

    override fun command(context: IntentionContext): SExp =
        IdeCommands.makeLemma(context.line1, context.name)

    override fun applyEdit(document: Document, context: IntentionContext, result: IdeResult) {
        val lemma = ResultDecoder.parseMetaVarLemma(result.payload) ?: return
        // Replace the hole first so line numbers below stay valid for the insert above.
        document.replaceString(context.tokenStartOffset, context.tokenEndOffset, lemma.application)
        val declLine0 = enclosingDeclarationLine(document, context.line1 - 1)
        document.insertString(document.getLineStartOffset(declLine0), lemma.lemmaSignature + "\n\n")
    }

    /** Scans up to the first line that starts at column 0 (the enclosing declaration). */
    private fun enclosingDeclarationLine(document: Document, fromLine0: Int): Int {
        val text = document.charsSequence
        for (line in fromLine0 downTo 0) {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            if (start < end && !text[start].isWhitespace()) return line
        }
        return 0
    }
}

/** `:make-case` — replaces the hole's line with a case expression. */
class MakeCaseIntention : IdrisIntentionBase("Make case") {
    override fun isAvailableForToken(token: PsiElement, document: Document): Boolean = isHole(token)

    override fun command(context: IntentionContext): SExp =
        IdeCommands.makeCase(context.line1, context.name)

    override fun applyEdit(document: Document, context: IntentionContext, result: IdeResult) {
        replaceLine(document, context.line1, result.text ?: return)
    }
}

/** `:make-with` — replaces the clause line with a `with` block. */
class MakeWithIntention : IdrisIntentionBase("Make with") {
    override fun isAvailableForToken(token: PsiElement, document: Document): Boolean =
        isHole(token) || isIdentifier(token)

    override fun command(context: IntentionContext): SExp =
        IdeCommands.makeWith(context.line1, context.name)

    override fun applyEdit(document: Document, context: IntentionContext, result: IdeResult) {
        replaceLine(document, context.line1, result.text ?: return)
    }
}
