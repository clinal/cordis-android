package io.github.clinal.cordis.runtime

class ProotCommandBuilder(private val paths: RuntimePaths) {
    fun cordisCommand(instanceId: String): List<String> {
        return shellCommand(
            instanceId = instanceId,
            command = """
                setsid sh <<'CORDIS_EOF'
                trap : SIGINT
                echo __PID__: ${'$'}${'$'}
                cd /home && PROOT_TMP_DIR=/tmp node .yarn/releases/yarn-4.14.1.cjs start
                status=${'$'}?
                echo __STATUS__: ${'$'}status
                echo -e '\n[Process exited.]\n\n'
                exit ${'$'}status
                CORDIS_EOF
            """.trimIndent(),
        )
    }

    fun loginShellCommand(instanceId: String): List<String> {
        return prootCommandPrefix(instanceId) + listOf(
            "-w",
            "/home",
            "/bin/login",
        )
    }

    fun shellCommand(instanceId: String, command: String): List<String> {
        return prootCommandPrefix(instanceId) + listOf(
            "/bin/sh",
            "/bin/login",
            "-c",
            command,
        )
    }

    private fun prootCommandPrefix(instanceId: String): List<String> {
        val instanceHome = paths.instanceHome(instanceId)
        val envRoot = paths.envFile.readText().trim()
        return listOf(
            paths.proot.absolutePath,
            "-r",
            "${paths.root.absolutePath}$envRoot",
            "-b",
            "${paths.tmp.absolutePath}:/tmp",
            "-b",
            "${paths.shm.absolutePath}:/dev/shm",
            "-b",
            "${paths.nixStore.absolutePath}:/nix",
            "-b",
            "${paths.root.absolutePath}:/data",
            "-b",
            "${instanceHome.absolutePath}:/home",
            "-b",
            "/proc:/proc",
            "-b",
            "/dev:/dev",
            "--sysvipc",
            "--link2symlink",
        )
    }
}
