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
                echo __STATUS__: ${'$'}?
                echo -e '\n[Process exited.]\n\n'
                CORDIS_EOF
            """.trimIndent(),
        )
    }

    fun shellCommand(instanceId: String, command: String): List<String> {
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
            "/bin/sh",
            "/bin/login",
            "-c",
            command,
        )
    }
}
