package io.github.clinal.cordis.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandBuilderTest {
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
}
