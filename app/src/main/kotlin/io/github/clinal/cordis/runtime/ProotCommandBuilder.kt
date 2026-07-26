package io.github.clinal.cordis.runtime

class ProotCommandBuilder(private val paths: RuntimePaths) {
    fun cordisCommand(instanceId: String): List<String> {
        val instanceHome = paths.instanceHome(instanceId)
        return listOf(
            paths.proot.absolutePath,
            "--link2symlink",
            "-0",
            "-r",
            paths.root.absolutePath,
            "-b",
            "${paths.nixStore.absolutePath}:/nix",
            "-b",
            "${paths.home.absolutePath}:/home",
            "-b",
            "${paths.tmp.absolutePath}:/tmp",
            "-b",
            "${paths.shm.absolutePath}:/dev/shm",
            "-b",
            "/proc:/proc",
            "-b",
            "/dev:/dev",
            "-w",
            instanceHome.absolutePath,
            "/usr/bin/env",
            "HOME=${instanceHome.absolutePath}",
            "TMPDIR=/tmp",
            "PROOT_TMP_DIR=${paths.tmp.absolutePath}",
            "node",
            "node_modules/cordis/bin.js",
        )
    }
}
