package io.github.clinal.cordis.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.clinal.cordis.data.BundleRegistry
import io.github.clinal.cordis.data.RegistryBundle
import io.github.clinal.cordis.ui.theme.CordisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BundleVersionsActivity : ComponentActivity() {
    private val registry by lazy { BundleRegistry(this) }
    private val bundleName by lazy { intent.getStringExtra(EXTRA_NAME).orEmpty() }
    private val picking by lazy { intent.getBooleanExtra(EXTRA_PICK, false) }
    private var bundles by mutableStateOf<List<RegistryBundle>>(emptyList())
    private var downloaded by mutableStateOf<Set<String>>(emptySet())
    private var busyId by mutableStateOf<String?>(null)
    private var progress by mutableStateOf<DownloadProgress?>(null)
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CordisTheme {
                BundleVersionsScreen(
                    name = bundleName,
                    bundles = bundles,
                    downloaded = downloaded,
                    busyId = busyId,
                    progress = progress,
                    message = message,
                    picking = picking,
                    onBack = ::finish,
                    onAction = ::act,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    registry.load().filter { it.name == bundleName }.let { it to registry.downloaded(it) }
                }
            }.onSuccess { (available, local) -> bundles = available; downloaded = local }
                .onFailure { message = it.message ?: "Cannot load bundle registry." }
        }
    }

    private fun act(bundle: RegistryBundle) {
        if (picking && bundle.id in downloaded) {
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(BundleManagerActivity.EXTRA_BUNDLE_NAME, bundle.name)
                    .putExtra(BundleManagerActivity.EXTRA_BUNDLE_VERSION, bundle.version),
            )
            finish()
            return
        }
        busyId = bundle.id
        progress = null
        message = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (!picking && bundle.id in downloaded) {
                        registry.delete(bundle)
                    } else {
                        registry.download(bundle) { current, total ->
                            runOnUiThread { progress = DownloadProgress(current, total) }
                        }
                    }
                    registry.downloaded(bundles)
                }
            }.onSuccess { downloaded = it }
                .onFailure { message = it.message ?: "Bundle operation failed." }
            busyId = null
            progress = null
        }
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PICK = "pick"

        fun intent(context: Context, name: String, pick: Boolean) =
            Intent(context, BundleVersionsActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_PICK, pick)
    }
}

@Composable
private fun BundleVersionsScreen(
    name: String,
    bundles: List<RegistryBundle>,
    downloaded: Set<String>,
    busyId: String?,
    progress: DownloadProgress?,
    message: String?,
    picking: Boolean,
    onBack: () -> Unit,
    onAction: (RegistryBundle) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(bundles, key = RegistryBundle::id) { bundle ->
                    val isDownloaded = bundle.id in downloaded
                    val isBusy = bundle.id == busyId
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Version ${bundle.version}", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = { onAction(bundle) }, enabled = busyId == null) {
                                Text(
                                    when {
                                        isBusy && !isDownloaded -> "Downloading"
                                        isBusy -> "Deleting"
                                        picking && isDownloaded -> "Select"
                                        isDownloaded -> "Delete"
                                        else -> "Download"
                                    },
                                )
                            }
                            if (isBusy && !isDownloaded) {
                                val total = progress?.total ?: -1L
                                if (total > 0L) {
                                    LinearProgressIndicator(
                                        progress = { (progress?.current ?: 0L).toFloat().div(total).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DownloadProgress(val current: Long, val total: Long)
