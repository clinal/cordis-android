package io.github.clinal.cordis.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.clinal.cordis.ui.theme.CordisTheme
import io.github.clinal.cordis.runtime.AndroidControlShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class InstanceSettingsActivity : ComponentActivity() {
    private val viewModel by viewModels<CordisViewModel>()
    private val shizukuError = mutableStateOf<String?>(null)
    private var pendingSave: PendingSave? = null
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Shizuku permission granted; verifying Android control UserService")
            pendingSave?.let(::verifyAndPersist)
        } else {
            pendingSave = null
            reportShizukuError(
                "Shizuku permission was denied. Grant cordis-android access in Shizuku, then save again.",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID).orEmpty()
        setContent {
            val instances by viewModel.instances.collectAsState()
            val instance = instances.firstOrNull { it.id == instanceId }

            CordisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        SettingsHeader(
                            subtitle = instance?.name ?: "Instance not found",
                            onBack = ::finish,
                        )

                        if (instance != null) {
                            InstanceSettingsPanel(
                                instance = instance,
                                androidControlError = shizukuError.value,
                                onSave = {
                                        name,
                                        port,
                                        dns,
                                        androidControlEnabled,
                                        hasWebService,
                                        patchPort,
                                        startCommand,
                                    ->
                                    validateAndSave(
                                        PendingSave(
                                            instance.id,
                                            name,
                                            port,
                                            dns,
                                            androidControlEnabled,
                                            hasWebService,
                                            patchPort,
                                            startCommand,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun validateAndSave(config: PendingSave) {
        shizukuError.value = null
        if (!config.androidControlEnabled) {
            persistAndFinish(config)
            return
        }
        try {
            validateShizukuAndSave(config)
        } catch (error: Exception) {
            pendingSave = null
            reportShizukuError(
                "Unable to check Shizuku: ${error.message ?: error.javaClass.simpleName}.",
                error,
            )
        }
    }

    private fun validateShizukuAndSave(config: PendingSave) {
        if (!Shizuku.pingBinder()) {
            reportShizukuError(
                "Cannot enable Android control because Shizuku is not running. Start Shizuku, then save again.",
            )
            return
        }
        if (Shizuku.isPreV11()) {
            reportShizukuError("Cannot enable Android control because Shizuku 11 or newer is required.")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            verifyAndPersist(config)
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            reportShizukuError(
                "Shizuku permission is required. Grant cordis-android access in Shizuku, then save again.",
            )
            return
        }

        pendingSave = config
        Log.i(TAG, "Requesting Shizuku permission before enabling Android control")
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    private fun verifyAndPersist(config: PendingSave) {
        pendingSave = config
        shizukuError.value = "Connecting to the Shizuku Android control service…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val shell = AndroidControlShell(applicationContext)
                    try {
                        shell.execute("true")
                    } finally {
                        shell.close()
                    }
                }
            }
            result.onSuccess {
                Log.i(TAG, "Shizuku Android control UserService verified; saving setting")
                persistAndFinish(config)
            }.onFailure { error ->
                pendingSave = null
                reportShizukuError(
                    "Could not connect to the Shizuku Android control service: " +
                        (error.message ?: error.javaClass.simpleName),
                    error,
                )
            }
        }
    }

    private fun persistAndFinish(config: PendingSave) {
        pendingSave = null
        viewModel.updateInstanceConfig(
            config.instanceId,
            config.name,
            config.port,
            config.dns,
            config.androidControlEnabled,
            config.hasWebService,
            config.patchPort,
            config.startCommand,
        )
        finish()
    }

    private fun reportShizukuError(message: String, error: Throwable? = null) {
        shizukuError.value = message
        if (error == null) Log.w(TAG, message) else Log.e(TAG, message, error)
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "InstanceSettings"
    }
}

private data class PendingSave(
    val instanceId: String,
    val name: String,
    val port: Int,
    val dns: String,
    val androidControlEnabled: Boolean,
    val hasWebService: Boolean,
    val patchPort: Boolean,
    val startCommand: String,
)

@Composable
private fun SettingsHeader(
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
