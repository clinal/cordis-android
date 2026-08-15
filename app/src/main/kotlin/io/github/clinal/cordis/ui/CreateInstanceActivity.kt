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
import io.github.clinal.cordis.data.BundleRegistry
import io.github.clinal.cordis.data.RegistryBundle
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
    private var registryBundles by mutableStateOf<List<RegistryBundle>>(emptyList())
    private var selectedRegistryBundle by mutableStateOf<RegistryBundle?>(null)
    private var environment by mutableStateOf<Map<String, String>>(emptyMap())

    private val environmentEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            environment = EnvironmentVariablesActivity.environmentFrom(result.data)
        }
    }

    private val packagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        packageUri = uri
        selectedPackageName = contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        } ?: uri.lastPathSegment
        errorMessage = null
    }

    private val registryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val name = result.data?.getStringExtra(BundleManagerActivity.EXTRA_BUNDLE_NAME)
        val version = result.data?.getStringExtra(BundleManagerActivity.EXTRA_BUNDLE_VERSION)
        lifecycleScope.launch {
            val available = withContext(Dispatchers.IO) { BundleRegistry(this@CreateInstanceActivity).load() }
            registryBundles = available
            selectedRegistryBundle = available.find { it.name == name && it.version == version }
            if (selectedRegistryBundle == null) errorMessage = "Selected registry package is no longer available."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as CordisApplication).instanceRepository
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val registry = BundleRegistry(this@CreateInstanceActivity)
                    registry.load()
                }
            }.onSuccess { registryBundles = it }
                .onFailure { error ->
                errorMessage = error.message ?: "Cannot load bundle registry."
            }
        }
        setContent {
            CordisTheme {
                CreateInstanceScreen(
                    packageName = selectedPackageName,
                    creating = creating,
                    progress = progress,
                    errorMessage = errorMessage,
                    registryBundle = selectedRegistryBundle,
                    suggestedPort = repository.suggestedPort(),
                    environmentCount = environment.size,
                    onBack = ::finish,
                    onSelectPackage = {
                        packagePicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/gzip",
                                "application/x-gzip",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onSelectRegistryPackage = {
                        registryPicker.launch(BundleManagerActivity.pickerIntent(this))
                    },
                    onEditEnvironment = {
                        environmentEditor.launch(EnvironmentVariablesActivity.intent(this, environment))
                    },
                    onCreate = ::createInstance,
                )
            }
        }
    }

    private fun createInstance(
        name: String,
        useCustomPackage: Boolean,
        registryBundle: RegistryBundle?,
        port: Int,
        androidControlEnabled: Boolean,
        hasWebService: Boolean,
        patchPort: Boolean,
        startCommand: String,
    ) {
        val selectedPackage = packageUri
        if (useCustomPackage && registryBundle == null && selectedPackage == null) {
            errorMessage = "Select a ZIP or tar.gz package first."
            return
        }

        val config = PendingCreate(
            name,
            useCustomPackage,
            registryBundle,
            port,
            androidControlEnabled,
            hasWebService,
            patchPort,
            startCommand,
            environment,
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
                        environment = config.environment,
                    )
                    try {
                        if (config.registryBundle != null) {
                            val registry = BundleRegistry(app)
                            RuntimeInstaller(app).installDownloadedPackage(
                                instanceId = instance.id,
                                packageFile = registry.archive(config.registryBundle),
                                onProgress = { message -> runOnUiThread { progress = message } },
                            )
                        } else if (selectedPackage != null && config.useCustomPackage) {
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
    registryBundle: RegistryBundle?,
    suggestedPort: Int,
    environmentCount: Int,
    onBack: () -> Unit,
    onSelectPackage: () -> Unit,
    onSelectRegistryPackage: () -> Unit,
    onEditEnvironment: () -> Unit,
    onCreate: (
        name: String,
        useCustomPackage: Boolean,
        registryBundle: RegistryBundle?,
        port: Int,
        androidControlEnabled: Boolean,
        hasWebService: Boolean,
        patchPort: Boolean,
        startCommand: String,
    ) -> Unit,
) {
    var name by androidx.compose.runtime.remember { mutableStateOf("") }
    var packageSource by androidx.compose.runtime.remember { mutableStateOf(PackageSource.BUILT_IN) }
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

            OutlinedButton(
                onClick = onEditEnvironment,
                enabled = !creating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Environment variables ($environmentCount)")
            }

            PackageOption(
                selected = packageSource == PackageSource.BUILT_IN,
                title = "Built-in template",
                description = "Create the instance from the bundled Cordis boilerplate.",
                enabled = !creating,
                onClick = { packageSource = PackageSource.BUILT_IN },
            )
            PackageOption(
                selected = packageSource == PackageSource.REGISTRY,
                title = "Registry package",
                description = "Choose a downloaded package and version from the registry.",
                enabled = !creating,
                onClick = { packageSource = PackageSource.REGISTRY },
            )
            if (packageSource == PackageSource.REGISTRY) {
                OutlinedButton(onClick = onSelectRegistryPackage, enabled = !creating) {
                    Text(
                        registryBundle?.let { "${it.name} ${it.version}" } ?: "Select registry package",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PackageOption(
                selected = packageSource == PackageSource.CUSTOM,
                title = "Custom package",
                description = "Extract your package directly into the new instance directory.",
                enabled = !creating,
                onClick = { packageSource = PackageSource.CUSTOM },
            )
            if (packageSource == PackageSource.CUSTOM) {
                OutlinedButton(onClick = onSelectPackage, enabled = !creating) {
                    Text(packageName ?: "Select ZIP or tar.gz package", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        packageSource == PackageSource.CUSTOM,
                        registryBundle.takeIf { packageSource == PackageSource.REGISTRY },
                        port ?: suggestedPort,
                        androidControlEnabled,
                        hasWebService,
                        patchPort,
                        startCommand,
                    )
                },
                enabled = !creating && portIsValid &&
                    (packageSource != PackageSource.CUSTOM || packageName != null) &&
                    (packageSource != PackageSource.REGISTRY || registryBundle != null),
            ) {
                Text("Create")
            }
        }
    }
}

private data class PendingCreate(
    val name: String,
    val useCustomPackage: Boolean,
    val registryBundle: RegistryBundle?,
    val port: Int,
    val androidControlEnabled: Boolean,
    val hasWebService: Boolean,
    val patchPort: Boolean,
    val startCommand: String,
    val environment: Map<String, String>,
)

private enum class PackageSource { BUILT_IN, CUSTOM, REGISTRY }

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
