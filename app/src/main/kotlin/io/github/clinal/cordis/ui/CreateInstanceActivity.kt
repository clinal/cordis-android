package io.github.clinal.cordis.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.clinal.cordis.CordisApplication
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
        setContent {
            CordisTheme {
                CreateInstanceScreen(
                    packageName = selectedPackageName,
                    creating = creating,
                    progress = progress,
                    errorMessage = errorMessage,
                    onBack = ::finish,
                    onSelectPackage = { packagePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onCreate = ::createInstance,
                )
            }
        }
    }

    private fun createInstance(name: String, useCustomPackage: Boolean) {
        val selectedPackage = packageUri
        if (useCustomPackage && selectedPackage == null) {
            errorMessage = "Select a ZIP package first."
            return
        }

        creating = true
        errorMessage = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = application as CordisApplication
                    val instance = app.instanceRepository.addInstance(name)
                    try {
                        if (selectedPackage != null && useCustomPackage) {
                            RuntimeInstaller(app).installCustomPackage(
                                instanceId = instance.id,
                                packageUri = selectedPackage,
                                port = instance.port,
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
    onBack: () -> Unit,
    onSelectPackage: () -> Unit,
    onCreate: (name: String, useCustomPackage: Boolean) -> Unit,
) {
    var name by androidx.compose.runtime.remember { mutableStateOf("") }
    var useCustomPackage by androidx.compose.runtime.remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
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

            errorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            if (creating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(progress.ifBlank { "Creating instance." })
            }

            Button(
                modifier = Modifier.testTag("cordis.createInstance.confirm"),
                onClick = { onCreate(name, useCustomPackage) },
                enabled = !creating && (!useCustomPackage || packageName != null),
            ) {
                Text("Create")
            }
        }
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
