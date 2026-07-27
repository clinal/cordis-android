package io.github.clinal.cordis.runtime

import kotlin.test.Test
import kotlin.test.assertTrue

class ProotCommandBuilderTest {
    @Test
    fun cordisStartupScriptPreservesNodeExitStatus() {
        val script = cordisStartupScript()

        assertTrue(script.contains("status=${'$'}?"))
        assertTrue(script.contains("echo __STATUS__: ${'$'}status"))
        assertTrue(script.contains("exit ${'$'}status"))
        assertTrue(script.indexOf("status=${'$'}?") < script.indexOf("echo -e"))
        assertTrue(script.indexOf("echo -e") < script.indexOf("exit ${'$'}status"))
    }
}
