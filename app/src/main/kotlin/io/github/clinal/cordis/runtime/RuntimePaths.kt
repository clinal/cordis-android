package io.github.clinal.cordis.runtime

import android.content.Context
import java.io.File

class RuntimePaths(context: Context) {
    val filesDir: File = context.filesDir
    val root: File = context.filesDir.resolve("data")
    val nixStore: File = root.resolve("nix")
    val home: File = root.resolve("home")
    val tmp: File = root.resolve("tmp")
    val shm: File = root.resolve("shm")
    val proot: File = root.resolve("proot-static")
    val envFile: File = context.filesDir.resolve("env.txt")
    val resolvConf: File = context.filesDir.resolve("resolv.conf")

    fun instanceHome(instanceId: String): File = home.resolve("instances").resolve(instanceId)
}
