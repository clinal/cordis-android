package io.github.clinal.cordis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.domain.AppSettings
import io.github.clinal.cordis.domain.CordisInstance
import io.github.clinal.cordis.domain.RuntimeStatus

@Composable
fun CordisApp(viewModel: CordisViewModel = viewModel()) {
    val instances by viewModel.instances.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var showingSettings by remember { mutableStateOf(false) }

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
            Header(
                showingSettings = showingSettings,
                instanceCount = instances.size,
                onAddInstance = viewModel::addInstance,
                onOpenTerminal = viewModel::openGlobalTerminal,
                onOpenSettings = { showingSettings = true },
                onCloseSettings = { showingSettings = false },
            )

            if (showingSettings) {
                SettingsPanel(
                    settings = settings,
                    onSaveBasePort = viewModel::updateBasePort,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(instances, key = { it.id }) { instance ->
                        InstancePanel(
                            instance = instance,
                            removable = instance.id != InstanceRepository.DEFAULT_INSTANCE_ID,
                            onStart = { viewModel.start(instance.id) },
                            onStop = { viewModel.stop(instance.id) },
                            onOpenTerminal = { viewModel.openInstanceTerminal(instance.id) },
                            onRemove = { viewModel.removeInstance(instance.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    showingSettings: Boolean,
    instanceCount: Int,
    onAddInstance: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = if (showingSettings) "Settings" else "Cordis",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (showingSettings) {
                    "Runtime ports"
                } else {
                    "$instanceCount instance${if (instanceCount == 1) "" else "s"}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showingSettings) {
                IconButton(onClick = onCloseSettings) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                IconButton(onClick = onOpenTerminal) {
                    Icon(Icons.Default.Terminal, contentDescription = "Global terminal")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
                Button(onClick = onAddInstance) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Instance")
                }
            }
        }
    }
}

@Composable
private fun InstancePanel(
    instance: CordisInstance,
    removable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTerminal: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = instance.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "localhost:${instance.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(instance.status)
                    IconButton(
                        onClick = onStart,
                        enabled = instance.status.canStart,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start ${instance.name}")
                    }
                    IconButton(onClick = onOpenTerminal) {
                        Icon(Icons.Default.Terminal, contentDescription = "Open ${instance.name} terminal")
                    }
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop ${instance.name}")
                    }
                    if (removable) {
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${instance.name}")
                        }
                    }
                }
            }

            if (instance.status == RuntimeStatus.Starting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LogPanel(lines = instance.lastLogLines)
        }
    }
}

private val RuntimeStatus.canStart: Boolean
    get() = this != RuntimeStatus.Starting && this != RuntimeStatus.Running

@Composable
private fun SettingsPanel(
    settings: AppSettings,
    onSaveBasePort: (Int) -> Unit,
) {
    var basePortText by remember(settings.basePort) { mutableStateOf(settings.basePort.toString()) }
    val parsedPort = basePortText.toIntOrNull()
    val portIsValid = parsedPort != null && parsedPort in 1024..65535

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Base port",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Instances use this port and increment by one in list order.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = basePortText,
                    onValueChange = { basePortText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    isError = basePortText.isNotBlank() && !portIsValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = { parsedPort?.let(onSaveBasePort) },
                    enabled = portIsValid,
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("Save")
                }
            }
            TextButton(onClick = { basePortText = InstanceRepository.DEFAULT_BASE_PORT.toString() }) {
                Text("Reset to ${InstanceRepository.DEFAULT_BASE_PORT}")
            }
        }
    }
}

@Composable
private fun StatusChip(status: RuntimeStatus) {
    val label = when (status) {
        RuntimeStatus.MissingBootstrap -> "Bootstrap missing"
        RuntimeStatus.Stopped -> "Stopped"
        RuntimeStatus.Starting -> "Starting"
        RuntimeStatus.Running -> "Running"
        RuntimeStatus.Failed -> "Failed"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

@Composable
private fun LogPanel(lines: List<String>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFF101418))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(lines.takeLast(80)) { line ->
            Text(
                text = line,
                color = Color(0xFFE6EDF3),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
