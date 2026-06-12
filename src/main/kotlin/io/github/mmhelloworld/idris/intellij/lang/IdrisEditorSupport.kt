package io.github.mmhelloworld.idris.intellij.lang

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class IdrisCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "-- "
    override fun getBlockCommentPrefix(): String = "{-"
    override fun getBlockCommentSuffix(): String = "-}"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class IdrisBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(IdrisTokenTypes.LPAREN, IdrisTokenTypes.RPAREN, false),
        BracePair(IdrisTokenTypes.LBRACKET, IdrisTokenTypes.RBRACKET, false),
        BracePair(IdrisTokenTypes.LBRACE, IdrisTokenTypes.RBRACE, false),
    )

    override fun getPairs(): Array<BracePair> = pairs

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}
