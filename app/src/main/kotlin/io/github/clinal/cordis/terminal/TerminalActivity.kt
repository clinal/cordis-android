package io.github.clinal.cordis.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.clinal.cordis.CordisApplication
import io.github.clinal.cordis.runtime.ProotCommandBuilder
import io.github.clinal.cordis.runtime.RuntimeInstaller
import io.github.clinal.cordis.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : ComponentActivity(), TerminalViewClient, TerminalSessionClient {
    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = if (intent.mode == Mode.INSTANCE) "Cordis terminal" else "Global terminal"
        showStatus("Preparing terminal.")

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { buildSessionSpec() }
            }.onSuccess { spec ->
                openTerminal(spec)
            }.onFailure { error ->
                Log.e(TAG, "Failed to open terminal", error)
                showStatus(error.message ?: "Terminal could not be opened.")
            }
        }
    }

    override fun onDestroy() {
        terminalSession?.finishIfRunning()
        terminalSession = null
        super.onDestroy()
    }

    private fun buildSessionSpec(): SessionSpec {
        return when (intent.mode) {
            Mode.INSTANCE -> buildInstanceSessionSpec()
            Mode.GLOBAL -> buildGlobalSessionSpec()
        }
    }

    private fun buildInstanceSessionSpec(): SessionSpec {
        val app = application as CordisApplication
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
            ?: error("Missing Cordis instance id.")
        val instance = app.instanceRepository.instance(instanceId)
            ?: error("Cordis instance was not found.")
        val installer = RuntimeInstaller(app)
        val paths = RuntimePaths(app)

        installer.prepare(instance.id, instance.port)
        check(installer.isBootstrapInstalled()) {
            "Runtime bootstrap assets are not packaged in this build."
        }

        val command = ProotCommandBuilder(paths).cordisCommand(instance.id)
        return SessionSpec(
            shellPath = command.first(),
            cwd = paths.filesDir.absolutePath,
            args = command.toTypedArray(),
            env = baseEnv(paths) + "PROOT_TMP_DIR=${paths.tmp.absolutePath}",
        )
    }

    private fun buildGlobalSessionSpec(): SessionSpec {
        val paths = RuntimePaths(this)
        paths.filesDir.mkdirs()
        return SessionSpec(
            shellPath = SYSTEM_SHELL,
            cwd = paths.filesDir.absolutePath,
            args = arrayOf(
                SYSTEM_SHELL,
                "-c",
                "echo 'Cordis Android shell'; export PS1='cordis $ '; exec $SYSTEM_SHELL -i",
            ),
            env = baseEnv(paths),
        )
    }

    private fun baseEnv(paths: RuntimePaths): Array<String> {
        return arrayOf(
            "TERM=xterm-256color",
            "HOME=${paths.filesDir.absolutePath}",
            "TMPDIR=${cacheDir.absolutePath}",
            "PATH=/system/bin:/system/xbin",
        )
    }

    private fun openTerminal(spec: SessionSpec) {
        terminalView = TerminalView(this, null).apply {
            setBackgroundColor(Color.BLACK)
            setTerminalViewClient(this@TerminalActivity)
            setTextSize(14)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        terminalSession = TerminalSession(
            spec.shellPath,
            spec.cwd,
            spec.args,
            spec.env,
            TRANSCRIPT_ROWS,
            this,
        )
        setContentView(terminalView)
        terminalView.attachSession(terminalSession)
        terminalView.requestFocus()
    }

    private fun showStatus(message: String) {
        val status = TextView(this).apply {
            text = message
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        setContentView(status)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        if (::terminalView.isInitialized && changedSession == terminalSession) {
            terminalView.onScreenUpdated()
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        changedSession.title?.takeIf(String::isNotBlank)?.let { title = it }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Toast.makeText(this, "Terminal exited.", Toast.LENGTH_SHORT).show()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal text", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrEmpty()) {
            (session ?: terminalSession)?.emulator?.paste(text)
        }
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        if (::terminalView.isInitialized) terminalView.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent) {
        terminalView.requestFocus()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }

    private enum class Mode {
        GLOBAL,
        INSTANCE,
    }

    private data class SessionSpec(
        val shellPath: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
    )

    private val Intent.mode: Mode
        get() = if (getStringExtra(EXTRA_MODE) == MODE_INSTANCE) Mode.INSTANCE else Mode.GLOBAL

    companion object {
        private const val TAG = "TerminalActivity"
        private const val EXTRA_MODE = "io.github.clinal.cordis.extra.MODE"
        private const val EXTRA_INSTANCE_ID = "io.github.clinal.cordis.extra.INSTANCE_ID"
        private const val MODE_GLOBAL = "global"
        private const val MODE_INSTANCE = "instance"
        private const val SYSTEM_SHELL = "/system/bin/sh"
        private const val TRANSCRIPT_ROWS = 2000

        fun globalIntent(context: Context): Intent {
            return Intent(context, TerminalActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_GLOBAL)
        }

        fun instanceIntent(context: Context, instanceId: String): Intent {
            return Intent(context, TerminalActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_INSTANCE)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
        }
    }
}
