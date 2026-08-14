package io.github.clinal.cordis.runtime

import java.io.File

class ProotCommandBuilder(private val paths: RuntimePaths) {
    fun cordisCommand(
        instanceId: String,
        startCommand: String,
        environment: Map<String, String> = emptyMap(),
    ): List<String> {
        return shellCommand(
            instanceId = instanceId,
            command = cordisProcessCommand(startCommand, environment),
        )
    }

    fun loginShellCommand(instanceId: String): List<String> {
        return prootCommandPrefix(instanceId) + listOf(
            "/bin/sh",
            "/bin/login",
            "-i",
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

    internal fun packageExtractionCommand(
        target: File,
        archive: File,
        format: PackageArchiveFormat,
    ): List<String> {
        return prootCommandPrefix(target) + listOf(
            "-b",
            "${archive.absolutePath}:$PACKAGE_ARCHIVE_PATH",
        ) + packageExtractionArguments(format)
    }

    private fun prootCommandPrefix(instanceId: String): List<String> {
        return prootCommandPrefix(paths.instanceHome(instanceId))
    }

    private fun prootCommandPrefix(instanceHome: File): List<String> {
        val envRoot = paths.envFile.readText().trim()
        return listOf(
            paths.proot.absolutePath,
            "-r",
            "${paths.root.absolutePath}$envRoot",
            "-w",
            "/home",
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

    companion object {
        const val PACKAGE_ARCHIVE_PATH = "/tmp/cordis-package"
    }
}

internal fun packageExtractionArguments(format: PackageArchiveFormat): List<String> = when (format) {
    PackageArchiveFormat.Zip ->
        listOf("/bin/unzip", "-q", ProotCommandBuilder.PACKAGE_ARCHIVE_PATH, "-d", "/home")
    PackageArchiveFormat.TarGzip ->
        listOf("/bin/tar", "-xzf", ProotCommandBuilder.PACKAGE_ARCHIVE_PATH, "-C", "/home")
}

internal fun cordisProcessCommand(
    startCommand: String,
    environment: Map<String, String>,
): String {
    val exports = environment.entries.joinToString("\n") { (key, value) ->
        "export $key=${value.shellQuote()}"
    }
    val script = """
        trap : SIGINT
        echo __PID__: ${'$'}${'$'}
        $exports
        cd /home && PROOT_TMP_DIR=/tmp sh -lc ${startCommand.shellQuote()}
        status=${'$'}?
        echo __STATUS__: ${'$'}status
        echo -e '\n[Process exited.]\n\n'
        exit ${'$'}status
    """.trimIndent()
    return "setsid sh -c ${script.shellQuote()}"
}

private fun String.shellQuote(): String {
    return "'${replace("'", "'\"'\"'")}'"
}
