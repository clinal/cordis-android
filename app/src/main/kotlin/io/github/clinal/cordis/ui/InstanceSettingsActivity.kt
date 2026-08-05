package io.github.clinal.cordis.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.clinal.cordis.ui.theme.CordisTheme

class InstanceSettingsActivity : ComponentActivity() {
    private val viewModel by viewModels<CordisViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                                onSave = {
                                        name,
                                        port,
                                        dns,
                                        androidControlEnabled,
                                        hasWebService,
                                        patchPort,
                                        startCommand,
                                    ->
                                    viewModel.updateInstanceConfig(
                                        instance.id,
                                        name,
                                        port,
                                        dns,
                                        androidControlEnabled,
                                        hasWebService,
                                        patchPort,
                                        startCommand,
                                    )
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"
    }
}

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
