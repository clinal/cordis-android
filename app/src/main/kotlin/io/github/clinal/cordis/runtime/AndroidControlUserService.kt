package io.github.clinal.cordis.runtime

import org.json.JSONObject
import java.util.concurrent.Executors

class AndroidControlUserService : IAndroidControlService.Stub() {
    override fun execute(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).start()
        val readers = Executors.newFixedThreadPool(2)
        return try {
            val stdout = readers.submit<String> { process.inputStream.bufferedReader().readText() }
            val stderr = readers.submit<String> { process.errorStream.bufferedReader().readText() }
            val exitCode = process.waitFor()
            JSONObject()
                .put("stdout", stdout.get())
                .put("stderr", stderr.get())
                .put("exitCode", exitCode)
                .toString()
        } finally {
            readers.shutdownNow()
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
