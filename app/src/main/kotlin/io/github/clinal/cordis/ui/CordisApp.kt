package io.github.clinal.cordis.ui

import android.content.Intent
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clinal.cordis.domain.CordisInstance
import io.github.clinal.cordis.domain.RuntimeStatus

@Composable
fun CordisApp(viewModel: CordisViewModel = viewModel()) {
    val context = LocalContext.current
    val instances by viewModel.instances.collectAsState()
    var pendingDelete by remember { mutableStateOf<CordisInstance?>(null) }

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
                instanceCount = instances.size,
                onAddInstance = viewModel::addInstance,
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(instances, key = { it.id }) { instance ->
                    InstancePanel(
                        instance = instance,
                        onStart = { viewModel.start(instance.id) },
                        onStop = { viewModel.stop(instance.id) },
                        onOpenConsole = {
                            context.startActivity(
                                Intent(context, ConsoleActivity::class.java)
                                    .putExtra(ConsoleActivity.EXTRA_URL, instance.consoleUrl),
                            )
                        },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(context, InstanceSettingsActivity::class.java)
                                    .putExtra(InstanceSettingsActivity.EXTRA_INSTANCE_ID, instance.id),
                            )
                        },
                        onRemove = { pendingDelete = instance },
                    )
                }
            }
        }
    }

    pendingDelete?.let { instance ->
        DeleteInstanceDialog(
            instance = instance,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.removeInstance(instance.id)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun Header(
    instanceCount: Int,
    onAddInstance: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Cordis",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$instanceCount instance${if (instanceCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onAddInstance) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Instance")
        }
    }
}

@Composable
private fun InstancePanel(
    instance: CordisInstance,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenSettings: () -> Unit,
    onRemove: () -> Unit,
) {
    var autoScroll by remember(instance.id) { mutableStateOf(true) }

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
                        text = instance.consoleUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(instance.status)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { autoScroll = !autoScroll }) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (autoScroll) {
                            "Disable ${instance.name} log auto-scroll"
                        } else {
                            "Enable ${instance.name} log auto-scroll"
                        },
                        tint = if (autoScroll) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                IconButton(
                    onClick = onOpenConsole,
                    enabled = instance.status == RuntimeStatus.Running,
                ) {
                    Icon(Icons.Default.Language, contentDescription = "Open ${instance.name} console")
                }
                IconButton(
                    onClick = onStart,
                    enabled = instance.status.canStart,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start ${instance.name}")
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop ${instance.name}")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Configure ${instance.name}")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove ${instance.name}")
                }
            }

            if (instance.status == RuntimeStatus.Starting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LogPanel(lines = instance.lastLogLines, autoScroll = autoScroll)
        }
    }
}

private val CordisInstance.consoleUrl: String
    get() = "http://127.0.0.1:$port"

private val RuntimeStatus.canStart: Boolean
    get() = this != RuntimeStatus.Starting && this != RuntimeStatus.Running && this != RuntimeStatus.Stopping

@Composable
fun InstanceSettingsPanel(
    instance: CordisInstance,
    onSave: (name: String, port: Int, dns: String) -> Unit,
) {
    var nameText by remember(instance.id, instance.name) { mutableStateOf(instance.name) }
    var portText by remember(instance.id, instance.port) { mutableStateOf(instance.port.toString()) }
    var dnsText by remember(instance.id, instance.dns) { mutableStateOf(instance.dns) }
    val parsedPort = portText.toIntOrNull()
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
                text = "Instance configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = nameText,
                onValueChange = { nameText = it.take(64) },
                label = { Text("Instance name") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                isError = portText.isNotBlank() && !portIsValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = dnsText,
                onValueChange = { dnsText = it.take(64) },
                label = { Text("DNS") },
                placeholder = { Text("System default") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = { parsedPort?.let { onSave(nameText, it, dnsText) } },
                    enabled = portIsValid,
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun DeleteInstanceDialog(
    instance: CordisInstance,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${instance.name}?") },
        text = { Text("This removes the instance and deletes its files.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun StatusChip(status: RuntimeStatus) {
    val label = when (status) {
        RuntimeStatus.MissingBootstrap -> "Uninitialized"
        RuntimeStatus.Stopped -> "Stopped"
        RuntimeStatus.Starting -> "Starting"
        RuntimeStatus.Running -> "Running"
        RuntimeStatus.Stopping -> "Stopping"
        RuntimeStatus.Failed -> "Failed"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

@Composable
private fun LogPanel(lines: List<String>, autoScroll: Boolean) {
    val listState = rememberLazyListState()
    val visibleLines = lines.takeLast(80)

    LaunchedEffect(autoScroll, visibleLines.size, visibleLines.lastOrNull()) {
        if (autoScroll && visibleLines.isNotEmpty()) {
            listState.animateScrollToItem(visibleLines.lastIndex)
        }
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color(0xFF101418))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(visibleLines) { line ->
                Text(
                    text = line,
                    color = Color(0xFFE6EDF3),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
