package io.github.mmhelloworld.idrisintellij.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Determines the working directory for the `idris2 --ide-mode` process serving
 * a file: the nearest ancestor directory containing an `.ipkg` file (mirroring
 * the compiler's `findIpkg`, Idris/Package.idr), else the content root, else
 * the file's directory. `:load-file` paths must be sent relative to this root.
 */
object IdrisRootResolver {

    fun rootFor(project: Project, file: VirtualFile): Path = ReadAction.compute<Path, RuntimeException> {
        var dir: VirtualFile? = file.parent
        while (dir != null) {
            if (dir.children.any { !it.isDirectory && it.extension == "ipkg" }) {
                return@compute Paths.get(dir.path)
            }
            if (dir.path == project.basePath) break
            dir = dir.parent
        }
        val contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        if (contentRoot != null) return@compute Paths.get(contentRoot.path)
        Paths.get(file.parent?.path ?: project.basePath ?: ".")
    }

    fun relativePath(root: Path, file: VirtualFile): String =
        root.relativize(Paths.get(file.path)).toString()
}
