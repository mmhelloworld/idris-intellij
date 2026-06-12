package io.github.mmhelloworld.idris.intellij.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.mmhelloworld.idris.intellij.ide.IdrisIdeService
import io.github.mmhelloworld.idris.intellij.ide.IdrisRootResolver
import io.github.mmhelloworld.idris.intellij.ide.LoadResult
import io.github.mmhelloworld.idris.intellij.lang.IdrisColors
import io.github.mmhelloworld.idris.intellij.settings.IdrisSettings
import java.nio.file.Path
import java.nio.file.Paths

data class IdrisAnnotationInput(val file: PsiFile, val virtualFile: VirtualFile)

data class IdrisAnnotationResult(val load: LoadResult, val root: Path, val stamp: Long)

/**
 * Saves the file, runs `:load-file` through [IdrisIdeService], and converts the
 * collected `:warning` diagnostics and `:highlight-source` spans to annotations.
 *
 * Position conversion: the compiler sends 0-based line/column with an exclusive
 * end column.
 */
class IdrisExternalAnnotator : ExternalAnnotator<IdrisAnnotationInput, IdrisAnnotationResult>() {

    private companion object {
        val LOG = Logger.getInstance(IdrisExternalAnnotator::class.java)

        /** Message prefixes that stay warnings even when the load failed. */
        fun isWarningMessage(message: String): Boolean {
            val trimmed = message.trimStart()
            return trimmed.startsWith("Warning") || trimmed.contains("Deprecation", ignoreCase = true)
        }
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): IdrisAnnotationInput? {
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem) return null
        return IdrisAnnotationInput(file, virtualFile)
    }

    override fun doAnnotate(collectedInfo: IdrisAnnotationInput?): IdrisAnnotationResult? {
        val input = collectedInfo ?: return null
        val project = input.file.project
        val service = IdrisIdeService.getInstance(project)

        var stamp = 0L
        ApplicationManager.getApplication().invokeAndWait {
            val document = FileDocumentManager.getInstance().getDocument(input.virtualFile)
            if (document != null) {
                FileDocumentManager.getInstance().saveDocument(document)
                stamp = document.modificationStamp
            }
        }

        val future = service.ensureLoaded(input.virtualFile)
        val timeoutMs = IdrisSettings.getInstance().loadTimeoutSeconds * 1000L + 5000L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!future.isDone) {
            try {
                ProgressManager.checkCanceled()
            } catch (e: ProcessCanceledException) {
                throw e
            }
            if (System.currentTimeMillis() > deadline) return null
            Thread.sleep(50)
        }
        val load = try {
            future.get()
        } catch (e: Exception) {
            LOG.info("Idris load failed for ${input.virtualFile.path}: ${e.message}")
            return null
        }
        return IdrisAnnotationResult(load, service.rootFor(input.virtualFile), stamp)
    }

    override fun apply(file: PsiFile, annotationResult: IdrisAnnotationResult?, holder: AnnotationHolder) {
        val result = annotationResult ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        if (document.modificationStamp != result.stamp) return
        val virtualFile = file.virtualFile ?: return

        for (diagnostic in result.load.diagnostics) {
            if (!matchesFile(diagnostic.file, result.root, virtualFile.path)) continue
            val range = toRange(document, diagnostic.startLine, diagnostic.startCol, diagnostic.endLine, diagnostic.endCol)
                ?: continue
            val severity =
                if (result.load.success || isWarningMessage(diagnostic.message)) HighlightSeverity.WARNING
                else HighlightSeverity.ERROR
            holder.newAnnotation(severity, firstLineOf(diagnostic.message))
                .range(range)
                .tooltip("<pre>${escapeHtml(diagnostic.message)}</pre>")
                .create()
        }

        for (span in result.load.highlights) {
            if (!matchesFile(span.file, result.root, virtualFile.path)) continue
            val attributes = IdrisColors.forDecoration(span.decor) ?: continue
            val range = toRange(document, span.startLine, span.startCol, span.endLine, span.endCol) ?: continue
            if (range.isEmpty) continue
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .textAttributes(attributes)
                .create()
        }
    }

    /** Diagnostic/highlight filenames are root-relative (or occasionally absolute). */
    private fun matchesFile(reported: String, root: Path, absolutePath: String): Boolean {
        if (reported.isEmpty()) return false
        val resolved = try {
            val reportedPath = Paths.get(reported)
            if (reportedPath.isAbsolute) reportedPath.normalize() else root.resolve(reportedPath).normalize()
        } catch (_: Exception) {
            return false
        }
        return resolved == Paths.get(absolutePath).normalize()
    }

    /** Converts a 0-based, end-exclusive compiler span to a document range. */
    private fun toRange(document: Document, startLine: Int, startCol: Int, endLine: Int, endCol: Int): TextRange? {
        if (startLine < 0 || startLine >= document.lineCount) return null
        val safeEndLine = endLine.coerceIn(startLine, document.lineCount - 1)
        val start = (document.getLineStartOffset(startLine) + startCol)
            .coerceIn(0, document.textLength)
        var end = (document.getLineStartOffset(safeEndLine) + endCol)
            .coerceIn(start, document.textLength)
        if (end == start) {
            end = expandToWordEnd(document, start)
        }
        return if (end > start) TextRange(start, end) else TextRange(start, minOf(start + 1, document.textLength))
    }

    private fun expandToWordEnd(document: Document, offset: Int): Int {
        val text = document.charsSequence
        var end = offset
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '\'')) end++
        return end
    }

    private fun firstLineOf(message: String): String = message.lineSequence().firstOrNull()?.trim() ?: message

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
