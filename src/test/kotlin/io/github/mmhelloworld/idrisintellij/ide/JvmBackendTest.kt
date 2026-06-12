package io.github.mmhelloworld.idrisintellij.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JvmBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun executableWithJar(jarName: String?): String {
        val root = tmp.newFolder("exec")
        val appDir = root.resolve("idris2_app").apply { mkdirs() }
        if (jarName != null) appDir.resolve(jarName).writeText("")
        val launcher = root.resolve("idris2")
        launcher.writeText("#!/bin/sh\n")
        return launcher.absolutePath
    }

    @Test
    fun `reads the backend version from the assembler jar name`() {
        val exec = executableWithJar("idris-jvm-assembler-0.8.3.jar")
        assertEquals(JvmBackend.Version(0, 8, 3), JvmBackend.detectVersion(exec))
    }

    @Test
    fun `a snapshot counts as its base version`() {
        val exec = executableWithJar("idris-jvm-assembler-0.8.2-SNAPSHOT.jar")
        assertEquals(JvmBackend.Version(0, 8, 2), JvmBackend.detectVersion(exec))
    }

    @Test
    fun `no idris2_app directory means not a JVM backend`() {
        val root = tmp.newFolder("scheme")
        val launcher = root.resolve("idris2")
        launcher.writeText("#!/bin/sh\n")
        assertNull(JvmBackend.detectVersion(launcher.absolutePath))
    }

    @Test
    fun `unversioned jars are ignored`() {
        val exec = executableWithJar("asm-9.9.1.jar")
        assertNull(JvmBackend.detectVersion(exec))
    }

    @Test
    fun `stale jars from in-place upgrades lose to the newest version`() {
        val exec = executableWithJar("idris-jvm-assembler-0.8.1-SNAPSHOT.jar")
        val appDir = java.io.File(exec).parentFile.resolve("idris2_app")
        appDir.resolve("idris-jvm-assembler-0.8.3.jar").writeText("")
        assertEquals(JvmBackend.Version(0, 8, 3), JvmBackend.detectVersion(exec))
    }

    @Test
    fun `versions compare numerically`() {
        assertTrue(JvmBackend.Version(0, 8, 2) < JvmBackend.MINIMUM)
        assertTrue(JvmBackend.Version(0, 8, 3) >= JvmBackend.MINIMUM)
        assertTrue(JvmBackend.Version(0, 9, 0) > JvmBackend.MINIMUM)
        assertTrue(JvmBackend.Version(1, 0, 0) > JvmBackend.Version(0, 99, 99))
    }
}
