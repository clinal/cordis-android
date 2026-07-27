package io.github.clinal.cordis.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
    private var fontSize = DEFAULT_FONT_SIZE
    private var lastBackPressedAt = 0L
    private var controlKey = false
    private var altKey = false
    private var controlButton: Button? = null
    private var altButton: Button? = null
    private var terminalFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = if (intent.mode == Mode.INSTANCE) "Cordis terminal" else "Global terminal"
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPressed()
                }
            },
        )
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBackPressed()
            return true
        }
        if (terminalFinished && event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
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

        val command = ProotCommandBuilder(paths).loginShellCommand(instance.id)
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
            setTextSize(fontSize)
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
        setContentView(createTerminalLayout())
        terminalView.attachSession(terminalSession)
        focusTerminal()
    }

    private fun createTerminalLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(
                terminalView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(createExtraKeysView())
        }
    }

    private fun createExtraKeysView(): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            setBackgroundColor(EXTRA_KEYS_BACKGROUND)
            isHorizontalScrollBarEnabled = false
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(6, 6, 6, 6)
                    controlButton = addExtraKey("CTRL") { toggleControlKey() }
                    altButton = addExtraKey("ALT") { toggleAltKey() }
                    addExtraKey("ESC") { sendText("\u001B") }
                    addExtraKey("TAB") { sendText("\t") }
                    addExtraKey("-") { sendText("-") }
                    addExtraKey("/") { sendText("/") }
                    addExtraKey("|") { sendText("|") }
                    addExtraKey("HOME") { sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME) }
                    addExtraKey("UP") { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
                    addExtraKey("END") { sendKeyCode(KeyEvent.KEYCODE_MOVE_END) }
                    addExtraKey("PGUP") { sendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
                    addExtraKey("LEFT") { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
                    addExtraKey("DOWN") { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
                    addExtraKey("RIGHT") { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
                    addExtraKey("PGDN") { sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
                    addExtraKey("BKSP") { sendKeyCode(KeyEvent.KEYCODE_DEL) }
                    addExtraKey("ENTER") { sendText("\r") }
                    addExtraKey("KBD") { focusTerminal() }
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun LinearLayout.addExtraKey(label: String, onClick: () -> Unit): Button {
        val button = Button(context).apply {
            text = label
            textSize = 12f
            setTextColor(EXTRA_KEYS_TEXT_COLOR)
            setBackgroundColor(EXTRA_KEYS_BUTTON_BACKGROUND)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(18, 8, 18, 8)
            setOnClickListener {
                onClick()
                focusTerminal()
            }
        }
        addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = 6
            },
        )
        return button
    }

    private fun toggleControlKey() {
        controlKey = !controlKey
        updateModifierButtons()
    }

    private fun toggleAltKey() {
        altKey = !altKey
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        updateModifierButton(controlButton, controlKey)
        updateModifierButton(altButton, altKey)
    }

    private fun updateModifierButton(button: Button?, active: Boolean) {
        button?.isActivated = active
        button?.setTextColor(if (active) EXTRA_KEYS_ACTIVE_TEXT_COLOR else EXTRA_KEYS_TEXT_COLOR)
        button?.setBackgroundColor(if (active) EXTRA_KEYS_ACTIVE_BACKGROUND else EXTRA_KEYS_BUTTON_BACKGROUND)
    }

    private fun sendKeyCode(keyCode: Int) {
        if (terminalFinished && keyCode == KeyEvent.KEYCODE_ENTER) {
            finish()
            return
        }

        val metaState = keyMetaState()
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        terminalView.onKeyDown(keyCode, event)
        resetModifierKeys()
    }

    private fun sendText(text: String) {
        if (terminalFinished && (text == "\r" || text == "\n")) {
            finish()
            return
        }

        text.codePoints().forEach { codePoint ->
            terminalView.inputCodePoint(
                codePoint,
                controlKey,
                altKey,
            )
        }
        resetModifierKeys()
    }

    private fun keyMetaState(): Int {
        var metaState = 0
        if (controlKey) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altKey) metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return metaState
    }

    private fun resetModifierKeys() {
        controlKey = false
        altKey = false
        updateModifierButtons()
    }

    private fun focusTerminal() {
        terminalView.requestFocus()
        terminalView.post {
            val inputMethodManager = getSystemService(InputMethodManager::class.java)
            inputMethodManager.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun handleBackPressed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressedAt <= BACK_EXIT_INTERVAL_MILLIS) {
            finish()
            return
        }

        lastBackPressedAt = now
        Toast.makeText(this, "Press back again to close terminal.", Toast.LENGTH_SHORT).show()
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
        if (finishedSession == terminalSession) terminalFinished = true
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

    override fun onScale(scale: Float): Float {
        if (scale in SCALE_DOWN_THRESHOLD..SCALE_UP_THRESHOLD) return scale

        val nextFontSize = if (scale > 1f) {
            (fontSize + FONT_SIZE_STEP).coerceAtMost(MAX_FONT_SIZE)
        } else {
            (fontSize - FONT_SIZE_STEP).coerceAtLeast(MIN_FONT_SIZE)
        }
        if (nextFontSize != fontSize) {
            fontSize = nextFontSize
            terminalView.setTextSize(fontSize)
        }
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        focusTerminal()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (terminalFinished && keyCode == KeyEvent.KEYCODE_ENTER) {
            finish()
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlKey

    override fun readAltKey(): Boolean = altKey

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (controlKey || altKey) resetModifierKeys()
        return false
    }

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
        private const val BACK_EXIT_INTERVAL_MILLIS = 2000L
        private const val EXTRA_KEYS_BACKGROUND = 0xFF20262D.toInt()
        private const val EXTRA_KEYS_BUTTON_BACKGROUND = 0xFF111820.toInt()
        private const val EXTRA_KEYS_ACTIVE_BACKGROUND = 0xFF3D6A9F.toInt()
        private const val EXTRA_KEYS_TEXT_COLOR = 0xFFE8EEF5.toInt()
        private const val EXTRA_KEYS_ACTIVE_TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val DEFAULT_FONT_SIZE = 18
        private const val MIN_FONT_SIZE = 10
        private const val MAX_FONT_SIZE = 32
        private const val FONT_SIZE_STEP = 1
        private const val SCALE_DOWN_THRESHOLD = 0.9f
        private const val SCALE_UP_THRESHOLD = 1.1f

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
