package io.github.clinal.cordis.runtime

class ProotCommandBuilder(private val paths: RuntimePaths) {
    fun cordisCommand(instanceId: String): List<String> {
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
            "cd /home && PROOT_TMP_DIR=/tmp exec node .yarn/releases/yarn-4.14.1.cjs start",
        )
    }
}
