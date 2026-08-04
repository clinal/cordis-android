package io.github.clinal.cordis.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.domain.AndroidBridgeStatus
import io.github.clinal.cordis.domain.CordisButton
import io.github.clinal.cordis.domain.CordisInstance
import io.github.clinal.cordis.domain.HomeShortcut
import io.github.clinal.cordis.domain.RuntimeStatus
import java.util.Locale

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun CordisApp(viewModel: CordisViewModel = viewModel()) {
    val context = LocalContext.current
    val instances by viewModel.instances.collectAsState()
    val homeShortcuts by viewModel.homeShortcuts.collectAsState()
    val bootstrapInstallState by viewModel.bootstrapInstallState.collectAsState()
    var pendingDelete by remember { mutableStateOf<CordisInstance?>(null) }
    val actionsEnabled = !bootstrapInstallState.installing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
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
                    actionsEnabled = actionsEnabled,
                    onAddInstance = {
                        context.startActivity(Intent(context, CreateInstanceActivity::class.java))
                    },
                    onOpenTerminal = viewModel::openGlobalTerminal,
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        HomeShortcutsPanel(
                            homeShortcuts = homeShortcuts,
                            instances = instances,
                            actionsEnabled = actionsEnabled,
                            onClickHomeShortcut = viewModel::clickBridgeButton,
                            onRemoveHomeShortcut = viewModel::removeHomeShortcut,
                        )
                    }
                    items(instances, key = { it.id }) { instance ->
                        InstancePanel(
                            instance = instance,
                            homeShortcuts = homeShortcuts,
                            actionsEnabled = actionsEnabled,
                            onStart = { viewModel.start(instance.id) },
                            onStop = { viewModel.stop(instance.id) },
                            onClickBridgeButton = { buttonId -> viewModel.clickBridgeButton(instance.id, buttonId) },
                            onAddHomeShortcut = { button -> viewModel.addHomeShortcut(instance.id, button) },
                            onRemoveHomeShortcut = { buttonId -> viewModel.removeHomeShortcut(instance.id, buttonId) },
                            onOpenTerminal = { viewModel.openInstanceTerminal(instance.id) },
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

        if (bootstrapInstallState.installing) {
            BootstrapInstallOverlay(message = bootstrapInstallState.message)
        }
    }

    if (actionsEnabled) pendingDelete?.let { instance ->
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
private fun HomeShortcutsPanel(
    homeShortcuts: List<HomeShortcut>,
    instances: List<CordisInstance>,
    actionsEnabled: Boolean,
    onClickHomeShortcut: (instanceId: String, buttonId: String) -> Unit,
    onRemoveHomeShortcut: (instanceId: String, buttonId: String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Home shortcuts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (homeShortcuts.isEmpty()) {
                Text(
                    text = "Pin instance buttons as shortcuts to show them here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                homeShortcuts.sortedBy(HomeShortcut::sort).forEach { homeShortcut ->
                    val instance = instances.firstOrNull { it.id == homeShortcut.instanceId }
                    val button = instance?.bridgeButtons?.firstOrNull { it.id == homeShortcut.buttonId }
                    HomeShortcutRow(
                        homeShortcut = homeShortcut,
                        instance = instance,
                        button = button,
                        actionsEnabled = actionsEnabled,
                        onClick = { onClickHomeShortcut(homeShortcut.instanceId, homeShortcut.buttonId) },
                        onRemove = { onRemoveHomeShortcut(homeShortcut.instanceId, homeShortcut.buttonId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeShortcutRow(
    homeShortcut: HomeShortcut,
    instance: CordisInstance?,
    button: CordisButton?,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val enabled = actionsEnabled &&
        instance?.bridgeStatus == AndroidBridgeStatus.Connected &&
        button?.enabled != false &&
        button != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = button?.label ?: homeShortcut.title ?: homeShortcut.buttonId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = homeShortcutSubtitle(homeShortcut, instance, button),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onClick, enabled = enabled) {
                Text("Run")
            }
            IconButton(onClick = onRemove, enabled = actionsEnabled) {
                Icon(Icons.Default.Delete, contentDescription = "Remove home shortcut")
            }
        }
    }
}

private fun homeShortcutSubtitle(
    homeShortcut: HomeShortcut,
    instance: CordisInstance?,
    button: CordisButton?,
): String {
    if (instance == null) return "Instance was removed"
    val currentButton = button ?: CordisButton(
        id = homeShortcut.buttonId,
        label = homeShortcut.title ?: homeShortcut.buttonId,
        icon = homeShortcut.icon,
        description = homeShortcut.description,
        enabled = homeShortcut.enabled,
        disabledReason = homeShortcut.disabledReason,
    )
    if (button == null && currentButton.description == null && currentButton.enabled) {
        return "${instance.name} - button is unavailable"
    }
    if (!currentButton.enabled) return currentButton.disabledReason ?: "${instance.name} - disabled"
    return instance.name
}

@Composable
private fun Header(
    instanceCount: Int,
    actionsEnabled: Boolean,
    onAddInstance: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                modifier = Modifier.testTag("cordis.title"),
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onOpenTerminal, enabled = actionsEnabled) {
                Icon(Icons.Default.Terminal, contentDescription = "Global terminal")
            }
            Button(
                modifier = Modifier.testTag("cordis.addInstance"),
                onClick = onAddInstance,
                enabled = actionsEnabled,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Instance")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun InstancePanel(
    instance: CordisInstance,
    homeShortcuts: List<HomeShortcut>,
    actionsEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClickBridgeButton: (buttonId: String) -> Unit,
    onAddHomeShortcut: (button: CordisButton) -> Unit,
    onRemoveHomeShortcut: (buttonId: String) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenSettings: () -> Unit,
    onRemove: () -> Unit,
) {
    var autoScroll by remember(instance.id) { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .testTag("cordis.instance.${instance.id}")
            .combinedClickable(
                enabled = actionsEnabled,
                onClick = {},
                onLongClick = onRemove,
                onLongClickLabel = "Remove ${instance.name}",
            ),
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
                    if (instance.hasWebService) {
                        Text(
                            text = instance.consoleUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusChip(instance.status)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { autoScroll = !autoScroll }, enabled = actionsEnabled) {
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
                if (instance.hasWebService) {
                    IconButton(
                        onClick = onOpenConsole,
                        enabled = actionsEnabled && instance.status == RuntimeStatus.Running,
                    ) {
                        Icon(Icons.Default.Language, contentDescription = "Open ${instance.name} console")
                    }
                }
                IconButton(onClick = onOpenTerminal, enabled = actionsEnabled) {
                    Icon(Icons.Default.Terminal, contentDescription = "Open ${instance.name} terminal")
                }
                IconButton(
                    modifier = Modifier.testTag("cordis.instance.${instance.id}.start"),
                    onClick = onStart,
                    enabled = actionsEnabled && instance.status.canStart,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start ${instance.name}")
                }
                IconButton(
                    modifier = Modifier.testTag("cordis.instance.${instance.id}.stop"),
                    onClick = onStop,
                    enabled = actionsEnabled,
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop ${instance.name}")
                }
                IconButton(onClick = onOpenSettings, enabled = actionsEnabled) {
                    Icon(Icons.Default.Settings, contentDescription = "Configure ${instance.name}")
                }
            }

            if (instance.status == RuntimeStatus.Starting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            AndroidBridgePanel(
                instance = instance,
                homeShortcuts = homeShortcuts,
                actionsEnabled = actionsEnabled,
                onClickBridgeButton = onClickBridgeButton,
                onAddHomeShortcut = onAddHomeShortcut,
                onRemoveHomeShortcut = onRemoveHomeShortcut,
            )

            LogPanel(lines = instance.lastLogLines, autoScroll = autoScroll)
        }
    }
}

@Composable
private fun AndroidBridgePanel(
    instance: CordisInstance,
    homeShortcuts: List<HomeShortcut>,
    actionsEnabled: Boolean,
    onClickBridgeButton: (buttonId: String) -> Unit,
    onAddHomeShortcut: (button: CordisButton) -> Unit,
    onRemoveHomeShortcut: (buttonId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BridgeStatusText(instance)
        if (instance.bridgeButtons.isNotEmpty()) {
            instance.bridgeButtons.forEach { button ->
                val pinned = homeShortcuts.any { it.instanceId == instance.id && it.buttonId == button.id }
                BridgeButtonRow(
                    button = button,
                    pinned = pinned,
                    actionsEnabled = actionsEnabled && instance.bridgeStatus == AndroidBridgeStatus.Connected,
                    onClick = { onClickBridgeButton(button.id) },
                    onToggleHomeShortcut = {
                        if (pinned) onRemoveHomeShortcut(button.id) else onAddHomeShortcut(button)
                    },
                )
            }
        }
    }
}

@Composable
private fun BridgeStatusText(instance: CordisInstance) {
    if (instance.status != RuntimeStatus.Running || instance.bridgeStatus == AndroidBridgeStatus.Connected) return
    Text(
        text = "Cordis Android plugin is not connected. Install and enable cordis-plugin-android in this instance.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BridgeButtonRow(
    button: CordisButton,
    pinned: Boolean,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onToggleHomeShortcut: () -> Unit,
) {
    val enabled = actionsEnabled && button.enabled
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = button.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                button.description != null && button.enabled -> button.description
                !button.enabled -> button.disabledReason ?: "Disabled"
                else -> button.id
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onClick, enabled = enabled) {
                Text("Run")
            }
            IconButton(onClick = onToggleHomeShortcut, enabled = actionsEnabled) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (pinned) "Remove from home" else "Pin to home",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    androidControlError: String? = null,
    onSave: (
        name: String,
        port: Int,
        dns: String,
        androidControlEnabled: Boolean,
        hasWebService: Boolean,
        patchPort: Boolean,
        startCommand: String,
    ) -> Unit,
) {
    var nameText by remember(instance.id, instance.name) { mutableStateOf(instance.name) }
    var portText by remember(instance.id, instance.port) { mutableStateOf(instance.port.toString()) }
    var dnsText by remember(instance.id, instance.dns) { mutableStateOf(instance.dns) }
    var androidControlEnabled by remember(instance.id, instance.androidControlEnabled) {
        mutableStateOf(instance.androidControlEnabled)
    }
    var hasWebService by remember(instance.id, instance.hasWebService) { mutableStateOf(instance.hasWebService) }
    var patchPort by remember(instance.id, instance.patchPort) { mutableStateOf(instance.patchPort) }
    var startCommand by remember(instance.id, instance.startCommand) { mutableStateOf(instance.startCommand) }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Android control", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Allow this instance to control the device through Shizuku. Takes effect after restart.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = androidControlEnabled,
                    onCheckedChange = { androidControlEnabled = it },
                )
            }
            androidControlError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SettingsSwitch(
                checked = hasWebService,
                title = "Web service",
                onCheckedChange = { enabled ->
                    hasWebService = enabled
                    if (!enabled) patchPort = false
                },
            )
            if (hasWebService) {
                SettingsSwitch(
                    checked = patchPort,
                    title = "Patch port on start",
                    onCheckedChange = { patchPort = it },
                )
            }
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
                placeholder = { Text(InstanceRepository.DEFAULT_DNS) },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = startCommand,
                onValueChange = { startCommand = it },
                label = { Text("Start command") },
                placeholder = { Text(InstanceRepository.DEFAULT_START_COMMAND) },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        parsedPort?.let {
                            onSave(
                                nameText,
                                it,
                                dnsText,
                                androidControlEnabled,
                                hasWebService,
                                patchPort,
                                startCommand,
                            )
                        }
                    },
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
private fun SettingsSwitch(
    checked: Boolean,
    title: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
        RuntimeStatus.Stopped -> "Stopped"
        RuntimeStatus.Starting -> "Starting"
        RuntimeStatus.Running -> "Running"
        RuntimeStatus.Stopping -> "Stopping"
        RuntimeStatus.Failed -> "Failed"
    }
    AssistChip(
        modifier = Modifier.testTag("cordis.status.${status.name.lowercase(Locale.US)}"),
        onClick = {},
        label = { Text(label) },
    )
}

@Composable
private fun BootstrapInstallOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("cordis.bootstrapOverlay")
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { change -> change.consume() }
                    }
                }
            }
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Extracting runtime bootstrap",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = message.ifBlank { "Preparing Cordis runtime." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LogPanel(lines: List<String>, autoScroll: Boolean) {
    val listState = rememberLazyListState()
    val firstVisibleLine = (lines.size - MAX_VISIBLE_LOG_LINES).coerceAtLeast(0)
    val visibleLineCount = lines.size - firstVisibleLine

    LaunchedEffect(autoScroll, lines.size) {
        if (autoScroll && visibleLineCount > 0) {
            // Schedule the tail position for the next remeasure. A synchronous scroll can
            // collide with Compose's current measure/layout pass when a process starts.
            listState.requestScrollToItem(visibleLineCount - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFF101418))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(count = visibleLineCount) { index ->
            Text(
                text = lines[firstVisibleLine + index],
                color = Color(0xFFE6EDF3),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private const val MAX_VISIBLE_LOG_LINES = 80
