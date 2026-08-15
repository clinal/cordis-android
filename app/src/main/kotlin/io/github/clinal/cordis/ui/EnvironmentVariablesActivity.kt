package io.github.clinal.cordis.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.clinal.cordis.ui.theme.CordisTheme
import org.json.JSONObject

class EnvironmentVariablesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialEnvironment = environmentFrom(intent)
        setContent {
            CordisTheme {
                EnvironmentVariablesScreen(
                    initialEnvironment = initialEnvironment,
                    onBack = ::finish,
                    onSave = { environment ->
                        setResult(Activity.RESULT_OK, resultIntent(environment))
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ENVIRONMENT = "environment"

        fun intent(context: Context, environment: Map<String, String>): Intent {
            return Intent(context, EnvironmentVariablesActivity::class.java)
                .putExtra(EXTRA_ENVIRONMENT, JSONObject(environment).toString())
        }

        fun environmentFrom(intent: Intent?): Map<String, String> {
            val json = intent?.getStringExtra(EXTRA_ENVIRONMENT) ?: return emptyMap()
            return runCatching {
                val environment = JSONObject(json)
                environment.keys().asSequence().associateWith(environment::getString)
            }.getOrElse { emptyMap() }
        }

        private fun resultIntent(environment: Map<String, String>): Intent {
            return Intent().putExtra(EXTRA_ENVIRONMENT, JSONObject(environment).toString())
        }
    }
}

@Composable
private fun EnvironmentVariablesScreen(
    initialEnvironment: Map<String, String>,
    onBack: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    val entries = remember {
        mutableStateListOf<EnvironmentEntry>().apply {
            addAll(initialEnvironment.map { (name, value) -> EnvironmentEntry(name, value) })
        }
    }
    val duplicateNames = entries.map(EnvironmentEntry::name).filter(String::isNotBlank)
        .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val isValid = entries.all { it.name.matches(ENVIRONMENT_NAME) } && duplicateNames.isEmpty()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Environment variables", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Variables are available to the instance process after its next start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.forEachIndexed { index, entry ->
                EnvironmentEntryRow(
                    entry = entry,
                    duplicate = entry.name in duplicateNames,
                    onChange = { entries[index] = it },
                    onDelete = { entries.removeAt(index) },
                )
            }
            OutlinedButton(onClick = { entries += EnvironmentEntry() }, modifier = Modifier.fillMaxWidth()) {
                Text("Add variable")
            }
            Button(
                onClick = { onSave(entries.associate { it.name to it.value }) },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun EnvironmentEntryRow(
    entry: EnvironmentEntry,
    duplicate: Boolean,
    onChange: (EnvironmentEntry) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = entry.name,
                onValueChange = { onChange(entry.copy(name = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Name") },
                singleLine = true,
                isError = !entry.name.matches(ENVIRONMENT_NAME) || duplicate,
                supportingText = when {
                    duplicate -> ({ Text("Name must be unique") })
                    !entry.name.matches(ENVIRONMENT_NAME) -> ({ Text("Use letters, numbers, and underscores") })
                    else -> null
                },
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete variable")
            }
        }
        OutlinedTextField(
            value = entry.value,
            onValueChange = { onChange(entry.copy(value = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Value") },
        )
    }
}

private data class EnvironmentEntry(val name: String = "", val value: String = "")

private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
