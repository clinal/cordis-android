package io.github.clinal.cordis.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity

class ConsoleActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http://127.0.0.1:") }
            ?: DEFAULT_URL
        setContentView(
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                loadUrl(url)
            },
        )
    }

    companion object {
        const val EXTRA_URL = "console_url"
        private const val DEFAULT_URL = "http://127.0.0.1:3140"
    }
}
