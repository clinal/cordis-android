package io.github.clinal.cordis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.clinal.cordis.ui.CordisApp
import io.github.clinal.cordis.ui.theme.CordisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CordisTheme {
                CordisApp()
            }
        }
    }
}
