package io.github.mmhelloworld.idrisintellij.ide

import java.nio.file.Files
import java.nio.file.Path

/**
 * Detects the idris-jvm backend version from the distribution layout: the
 * `idris2` launcher script sits next to an `idris2_app/` directory whose jars
 * carry the backend version (e.g. `idris-jvm-assembler-0.8.3.jar`). This works
 * for both the release zip (`exec/idris2`) and source builds
 * (`build/exec/idris2`).
 *
 * Returns null when the layout is not recognized — Scheme-built compilers have
 * no `idris2_app` directory and need no version gate (the stdio bug the
 * minimum guards against is JVM-runtime-only).
 */
object JvmBackend {
    /** First published release with the fEOF stdio fix (0.8.2 was never released). */
    val MINIMUM = Version(0, 8, 3)

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        override fun toString(): String = "$major.$minor.$patch"
    }

    // A -SNAPSHOT of a version is assumed to carry that version's fixes.
    private val JAR_VERSION = Regex("""idris-jvm-.*-(\d+)\.(\d+)\.(\d+)(-SNAPSHOT)?\.jar""")

    fun detectVersion(executable: String): Version? {
        return try {
            val appDir = Path.of(executable).toAbsolutePath().parent?.resolve("idris2_app")
            if (appDir == null || !Files.isDirectory(appDir)) return null
            Files.newDirectoryStream(appDir).use { entries ->
                // In-place upgrades can leave stale older jars behind; trust the newest.
                entries.mapNotNull { JAR_VERSION.matchEntire(it.fileName.toString()) }
                    .map { match ->
                        val (major, minor, patch) = match.destructured
                        Version(major.toInt(), minor.toInt(), patch.toInt())
                    }
                    .maxOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }
}
