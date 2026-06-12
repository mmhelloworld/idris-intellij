package io.github.mmhelloworld.idris.intellij.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class IdrisFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, IdrisLanguage) {
    override fun getFileType(): FileType = IdrisFileType
    override fun toString(): String = "Idris file"
}

/**
 * Flat-tree parser: every token is a leaf child of the file node. All ide-mode
 * commands are line/name addressed, so no structural PSI is needed in v1.
 */
class IdrisParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = IdrisLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val rootMarker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        rootMarker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = IdrisTokenTypes.FILE

    override fun getCommentTokens(): TokenSet = IdrisTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = IdrisTokenTypes.STRINGS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = IdrisFile(viewProvider)
}
