package io.github.clinal.cordis.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
    private val picking by lazy { intent.getBooleanExtra(EXTRA_PICK, false) }
    private var bundles by mutableStateOf<List<RegistryBundle>>(emptyList())
    private var downloaded by mutableStateOf<Set<String>>(emptySet())
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    private val versionPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK, result.data)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CordisTheme {
                BundleManagerScreen(
                    bundles = bundles,
                    downloaded = downloaded,
                    loading = loading,
                    message = message,
                    picking = picking,
                    onBack = ::finish,
                    onRefresh = { load(refresh = true) },
                    onOpen = { name ->
                        val versionIntent = BundleVersionsActivity.intent(this, name, picking)
                        if (picking) versionPicker.launch(versionIntent) else startActivity(versionIntent)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        load(refresh = false)
    }

    private fun load(refresh: Boolean) {
        if (loading) return
        loading = true
        message = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val available = if (refresh) registry.fetch() else registry.load()
                    available to registry.downloaded(available)
                }
            }.onSuccess { (available, local) -> bundles = available; downloaded = local }
                .onFailure { message = it.message ?: "Cannot load bundle registry." }
            loading = false
        }
    }

    companion object {
        private const val EXTRA_PICK = "pick"
        const val EXTRA_BUNDLE_NAME = "bundleName"
        const val EXTRA_BUNDLE_VERSION = "bundleVersion"

        fun pickerIntent(context: Context) = Intent(context, BundleManagerActivity::class.java)
            .putExtra(EXTRA_PICK, true)
    }
}

@Composable
private fun BundleManagerScreen(
    bundles: List<RegistryBundle>,
    downloaded: Set<String>,
    loading: Boolean,
    message: String?,
    picking: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val groups = bundles.groupBy(RegistryBundle::name).values.sortedBy { it.first().name.lowercase() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    if (picking) "Select" else "Bundles",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (groups.isEmpty() && !loading) Text("No compatible bundles found.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(groups, key = { it.first().name }) { versions ->
                    val first = versions.first()
                    val downloadedCount = versions.count { it.id in downloaded }
                    Card(Modifier.fillMaxWidth().clickable { onOpen(first.name) }) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(first.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (first.description.isNotBlank()) Text(first.description)
                            Text("${versions.size} versions · $downloadedCount downloaded")
                        }
                    }
                }
            }
        }
    }
}
