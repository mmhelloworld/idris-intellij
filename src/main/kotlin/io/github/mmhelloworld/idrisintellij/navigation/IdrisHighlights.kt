package io.github.mmhelloworld.idrisintellij.navigation

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import io.github.mmhelloworld.idrisintellij.ide.LoadResult
import io.github.mmhelloworld.idrisintellij.protocol.SemanticSpan

/**
 * Helpers over the cached semantic highlights ([LoadResult.highlights]). Spans
 * carry 0-based, end-exclusive positions, which map directly to editor offsets.
 * Shared by go-to-definition and highlight-usages.
 */
object IdrisHighlights {

    /** The named highlight span whose occurrence covers [offset], if any. */
    fun spanAt(load: LoadResult, document: Document, offset: Int): SemanticSpan? {
        if (offset > document.textLength) return null
        val line0 = document.getLineNumber(offset)
        val col0 = offset - document.getLineStartOffset(line0)
        return load.highlights.firstOrNull { span ->
            span.name != null && span.startLine == line0 && col0 >= span.startCol && col0 < span.endCol
        }
    }

    /** Editor ranges of every occurrence sharing [qualifiedName] (single-line spans). */
    fun occurrencesOf(load: LoadResult, document: Document, qualifiedName: String): List<TextRange> =
        load.highlights.mapNotNull { span ->
            if (span.qualifiedName != qualifiedName || span.startLine != span.endLine) return@mapNotNull null
            if (span.startLine >= document.lineCount) return@mapNotNull null
            val lineStart = document.getLineStartOffset(span.startLine)
            val start = lineStart + span.startCol
            val end = lineStart + span.endCol
            if (start in 0..end && end <= document.textLength) TextRange(start, end) else null
        }
}
