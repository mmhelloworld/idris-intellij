package io.github.mmhelloworld.idrisintellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType

/**
 * Folding for Idris: each multi-line declaration body, a run of consecutive
 * imports, and comment blocks. Regions are derived from [IdrisDeclarationScanner]
 * and a lexer pass (the PSI tree is flat); placeholders are carried per region.
 */
class IdrisFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        // .lidr (bird-track) columns differ; the scanner is for plain .idr only.
        if (root.containingFile?.language?.isKindOf(IdrisLiterateLanguage) == true) return emptyArray()
        val text = document.charsSequence
        val regions = ArrayList<FoldingDescriptor>()
        val decls = IdrisDeclarationScanner.scan(text)

        // Multi-line declaration bodies (everything but the header line).
        for (decl in decls) {
            if (decl.kind == IdrisDeclKind.IMPORT || decl.kind == IdrisDeclKind.MODULE) continue
            addMultiLine(regions, root.node, document, decl.range.startOffset, decl.range.endOffset, "…", headerVisible = true)
        }

        // A run of consecutive imports collapses to one region.
        val imports = decls.filter { it.kind == IdrisDeclKind.IMPORT }
        if (imports.size > 1) {
            addMultiLine(regions, root.node, document, imports.first().range.startOffset, imports.last().range.endOffset, "import …", headerVisible = false)
        }

        addCommentRegions(regions, root.node, document, text)
        return regions.toTypedArray()
    }

    /** Adds a region spanning [start]..[end] when it covers more than one line. */
    private fun addMultiLine(
        out: MutableList<FoldingDescriptor>,
        node: ASTNode,
        document: Document,
        start: Int,
        end: Int,
        placeholder: String,
        headerVisible: Boolean,
    ) {
        if (end > document.textLength) return
        val startLine = document.getLineNumber(start)
        val endLine = document.getLineNumber(end)
        if (endLine <= startLine) return
        val foldStart = if (headerVisible) document.getLineEndOffset(startLine) else start
        if (end <= foldStart) return
        out.add(FoldingDescriptor(node, TextRange(foldStart, end), null, placeholder))
    }

    private fun addCommentRegions(
        out: MutableList<FoldingDescriptor>,
        node: ASTNode,
        document: Document,
        text: CharSequence,
    ) {
        val lexer = IdrisLexer()
        lexer.start(text, 0, text.length, 0)
        var runStart = -1
        var runEnd = -1
        var runLastLine = -2

        fun flushRun() {
            if (runStart >= 0 && document.getLineNumber(runEnd) > document.getLineNumber(runStart)) {
                out.add(FoldingDescriptor(node, TextRange(runStart, runEnd), null, "-- …"))
            }
            runStart = -1
        }

        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            when (type) {
                IdrisTokenTypes.BLOCK_COMMENT -> {
                    flushRun()
                    if (document.getLineNumber(end) > document.getLineNumber(start)) {
                        out.add(FoldingDescriptor(node, TextRange(start, end), null, "{- … -}"))
                    }
                }
                IdrisTokenTypes.LINE_COMMENT, IdrisTokenTypes.DOC_COMMENT -> {
                    val line = document.getLineNumber(start)
                    if (runStart >= 0 && line == runLastLine + 1) {
                        runEnd = end
                    } else {
                        flushRun()
                        runStart = start
                        runEnd = end
                    }
                    runLastLine = line
                }
                TokenType.WHITE_SPACE -> {} // keeps a comment run contiguous across line breaks
                else -> flushRun()
            }
            lexer.advance()
        }
        flushRun()
    }

    override fun getPlaceholderText(node: ASTNode): String? = "…"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
