package io.github.clinal.cordis.ui

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

class BundleManagerActivity : ComponentActivity() {
    private val registry by lazy { BundleRegistry(this) }
    private var bundles by mutableStateOf<List<RegistryBundle>>(emptyList())
    private var downloaded by mutableStateOf<Set<String>>(emptySet())
    private var busyId by mutableStateOf<String?>(null)
    private var downloadProgress by mutableStateOf<DownloadProgress?>(null)
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CordisTheme {
                BundleManagerScreen(
                    bundles,
                    downloaded,
                    busyId,
                    downloadProgress,
                    message,
                    ::finish,
                    ::load,
                    ::toggle,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    private fun load() {
        busyId = REGISTRY_BUSY_ID
        message = null
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { registry.fetch().let { it to registry.downloaded(it) } } }
                .onSuccess { (available, local) -> bundles = available; downloaded = local }
                .onFailure { message = it.message ?: "Cannot load bundle registry." }
            busyId = null
        }
    }

    private fun toggle(bundle: RegistryBundle) {
        busyId = bundle.id
        downloadProgress = null
        message = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (bundle.id in downloaded) {
                        registry.delete(bundle)
                    } else {
                        registry.download(bundle) { downloadedBytes, totalBytes ->
                            runOnUiThread {
                                downloadProgress = DownloadProgress(downloadedBytes, totalBytes)
                            }
                        }
                    }
                    registry.downloaded(bundles)
                }
            }.onSuccess { downloaded = it }
                .onFailure { message = it.message ?: "Bundle operation failed." }
            busyId = null
            downloadProgress = null
        }
    }

    companion object { private const val REGISTRY_BUSY_ID = "registry" }
}

@Composable
private fun BundleManagerScreen(
    bundles: List<RegistryBundle>, downloaded: Set<String>, busyId: String?,
    downloadProgress: DownloadProgress?, message: String?,
    onBack: () -> Unit, onRefresh: () -> Unit, onToggle: (RegistryBundle) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Bundles", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            if (busyId == "registry") LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (bundles.isEmpty() && busyId == null) {
                Text("No compatible bundles found.")
                OutlinedButton(onClick = onRefresh) { Text("Retry") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(bundles, key = RegistryBundle::id) { bundle ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(bundle.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Version ${bundle.version}")
                            if (bundle.description.isNotBlank()) Text(bundle.description)
                            Button(onClick = { onToggle(bundle) }, enabled = busyId == null) {
                                Text(
                                    when {
                                        busyId == bundle.id && bundle.id in downloaded -> "Deleting"
                                        busyId == bundle.id -> "Downloading"
                                        bundle.id in downloaded -> "Delete"
                                        else -> "Download"
                                    },
                                )
                            }
                            if (busyId == bundle.id && bundle.id !in downloaded) {
                                val totalBytes = downloadProgress?.totalBytes ?: -1L
                                if (totalBytes > 0L) {
                                    val fraction = (downloadProgress?.downloadedBytes ?: 0L)
                                        .toFloat().div(totalBytes).coerceIn(0f, 1f)
                                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long)
