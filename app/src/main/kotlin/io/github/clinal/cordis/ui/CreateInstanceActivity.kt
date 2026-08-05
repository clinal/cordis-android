package io.github.clinal.cordis.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.clinal.cordis.CordisApplication
import io.github.clinal.cordis.data.InstanceRepository
import io.github.clinal.cordis.runtime.RuntimeInstaller
import io.github.clinal.cordis.ui.theme.CordisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateInstanceActivity : ComponentActivity() {
    private var packageUri by mutableStateOf<Uri?>(null)
    private var selectedPackageName by mutableStateOf<String?>(null)
    private var creating by mutableStateOf(false)
    private var progress by mutableStateOf("")
    private var errorMessage by mutableStateOf<String?>(null)

    private val packagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        packageUri = uri
        selectedPackageName = contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        } ?: uri.lastPathSegment
        errorMessage = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as CordisApplication).instanceRepository
        setContent {
            CordisTheme {
                CreateInstanceScreen(
                    packageName = selectedPackageName,
                    creating = creating,
                    progress = progress,
                    errorMessage = errorMessage,
                    suggestedPort = repository.suggestedPort(),
                    onBack = ::finish,
                    onSelectPackage = { packagePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onCreate = ::createInstance,
                )
            }
        }
    }

    private fun createInstance(
        name: String,
        useCustomPackage: Boolean,
        port: Int,
        androidControlEnabled: Boolean,
        hasWebService: Boolean,
        patchPort: Boolean,
        startCommand: String,
    ) {
        val selectedPackage = packageUri
        if (useCustomPackage && selectedPackage == null) {
            errorMessage = "Select a ZIP package first."
            return
        }

        val config = PendingCreate(
            name,
            useCustomPackage,
            port,
            androidControlEnabled,
            hasWebService,
            patchPort,
            startCommand,
        )
        persist(config)
    }

    private fun persist(config: PendingCreate) {
        creating = true
        errorMessage = null
        val selectedPackage = packageUri
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = application as CordisApplication
                    val instance = app.instanceRepository.addInstance(
                        name = config.name,
                        port = config.port,
                        androidControlEnabled = config.androidControlEnabled,
                        hasWebService = config.hasWebService,
                        patchPort = config.patchPort,
                        startCommand = config.startCommand,
                    )
                    try {
                        if (selectedPackage != null && config.useCustomPackage) {
                            RuntimeInstaller(app).installCustomPackage(
                                instanceId = instance.id,
                                packageUri = selectedPackage,
                                onProgress = { message -> runOnUiThread { progress = message } },
                            )
                        }
                    } catch (error: Throwable) {
                        app.instanceRepository.removeInstance(instance.id)
                        throw error
                    }
                }
            }
            result.fold(
                onSuccess = { finish() },
                onFailure = { error ->
                    creating = false
                    errorMessage = error.message ?: "Failed to create the instance."
                },
            )
        }
    }
}

@Composable
private fun CreateInstanceScreen(
    packageName: String?,
    creating: Boolean,
    progress: String,
    errorMessage: String?,
    suggestedPort: Int,
    onBack: () -> Unit,
    onSelectPackage: () -> Unit,
    onCreate: (
        name: String,
        useCustomPackage: Boolean,
        port: Int,
        androidControlEnabled: Boolean,
        hasWebService: Boolean,
        patchPort: Boolean,
        startCommand: String,
    ) -> Unit,
) {
    var name by androidx.compose.runtime.remember { mutableStateOf("") }
    var useCustomPackage by androidx.compose.runtime.remember { mutableStateOf(false) }
    var portText by androidx.compose.runtime.remember(suggestedPort) { mutableStateOf(suggestedPort.toString()) }
    var androidControlEnabled by androidx.compose.runtime.remember { mutableStateOf(false) }
    var hasWebService by androidx.compose.runtime.remember { mutableStateOf(true) }
    var patchPort by androidx.compose.runtime.remember { mutableStateOf(true) }
    var startCommand by androidx.compose.runtime.remember {
        mutableStateOf(InstanceRepository.DEFAULT_START_COMMAND)
    }
    val port = portText.toIntOrNull()
    val portIsValid = !hasWebService || port != null && port in 1024..65535

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = !creating) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Create instance",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !creating,
                singleLine = true,
                label = { Text("Name (optional)") },
            )

            PackageOption(
                selected = !useCustomPackage,
                title = "Built-in template",
                description = "Create the instance from the bundled Cordis boilerplate.",
                enabled = !creating,
                onClick = { useCustomPackage = false },
            )
            PackageOption(
                selected = useCustomPackage,
                title = "Custom ZIP package",
                description = "Extract your package directly into the new instance directory.",
                enabled = !creating,
                onClick = { useCustomPackage = true },
            )

            if (useCustomPackage) {
                OutlinedButton(onClick = onSelectPackage, enabled = !creating) {
                    Text(packageName ?: "Select ZIP package", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            BooleanOption(
                checked = androidControlEnabled,
                title = "Android control",
                description = "Allow this instance to control the device through Shizuku.",
                enabled = !creating,
                onCheckedChange = { androidControlEnabled = it },
            )

            BooleanOption(
                checked = hasWebService,
                title = "Web service",
                description = "Show the WebView action for this instance.",
                enabled = !creating,
                onCheckedChange = { enabled ->
                    hasWebService = enabled
                    if (!enabled) patchPort = false
                },
            )
            if (hasWebService) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !creating,
                    singleLine = true,
                    label = { Text("Port") },
                    isError = portText.isNotBlank() && !portIsValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                BooleanOption(
                    checked = patchPort,
                    title = "Patch port on start",
                    description = "Update the server port in app.yml before each start.",
                    enabled = !creating,
                    onCheckedChange = { patchPort = it },
                )
            }

            OutlinedTextField(
                value = startCommand,
                onValueChange = { startCommand = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !creating,
                singleLine = true,
                label = { Text("Start command") },
                placeholder = { Text(InstanceRepository.DEFAULT_START_COMMAND) },
            )

            errorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            if (creating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(progress.ifBlank { "Creating instance." })
            }

            Button(
                modifier = Modifier.testTag("cordis.createInstance.confirm"),
                onClick = {
                    onCreate(
                        name,
                        useCustomPackage,
                        port ?: suggestedPort,
                        androidControlEnabled,
                        hasWebService,
                        patchPort,
                        startCommand,
                    )
                },
                enabled = !creating && portIsValid && (!useCustomPackage || packageName != null),
            ) {
                Text("Create")
            }
        }
    }
}

private data class PendingCreate(
    val name: String,
    val useCustomPackage: Boolean,
    val port: Int,
    val androidControlEnabled: Boolean,
    val hasWebService: Boolean,
    val patchPort: Boolean,
    val startCommand: String,
)

@Composable
private fun BooleanOption(
    checked: Boolean,
    title: String,
    description: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun PackageOption(
    selected: Boolean,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
