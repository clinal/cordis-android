package io.github.clinal.cordis.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotCommandBuilderTest {
    @Test
    fun loginShellCommandPassesInstanceEnvironmentThroughEnv() {
        val command = loginShellArguments(linkedMapOf("TOKEN" to "value with spaces"))

        assertTrue(command.contains("/usr/bin/env"))
        assertTrue(command.contains("CORDIS_INSTANCE_ENV_TOKEN=value with spaces"))
        assertFalse(command.contains("TOKEN=value with spaces"))
        assertEquals(listOf("/bin/login", "-i"), command.takeLast(2))
    }

    @Test
    fun cordisProcessCommandUsesQuotedScriptArgumentInsteadOfHeredoc() {
        val command = cordisProcessCommand(
            startCommand = "printf '%s\\n' \"hello world\"",
            environment = mapOf("GREETING" to "it's ready"),
        )

        assertTrue(command.startsWith("setsid sh -c '"))
        assertTrue(command.contains("export GREETING="))
        assertFalse(command.contains("CORDIS_EOF"))
        assertFalse(command.contains("<<"))
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
    }

    @Test
    fun packageFormatIsDetectedFromContent() {
        val gzip = File.createTempFile("package", ".bin").apply {
            writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0))
        }
        val zip = File.createTempFile("package", ".bin").apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }
        try {
            assertEquals(PackageArchiveFormat.TarGzip, detectPackageArchiveFormat(gzip))
            assertEquals(PackageArchiveFormat.Zip, detectPackageArchiveFormat(zip))
        } finally {
            gzip.delete()
            zip.delete()
        }
    }

    @Test
    fun tarGzipUsesTarExtraction() {
        assertEquals(
            listOf("/bin/tar", "-xzf", "/tmp/cordis-package", "-C", "/home"),
            packageExtractionArguments(PackageArchiveFormat.TarGzip),
        )
    }

    @Test
    fun cordisProcessCommandExportsConfiguredEnvironment() {
        val command = cordisProcessCommand(
            startCommand = "true",
            environment = linkedMapOf(
                "PLAIN" to "value",
                "QUOTED" to "it's safe",
            ),
        )

        assertTrue(command.contains("export PLAIN="))
        assertTrue(command.contains("export QUOTED="))
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
    }
}
