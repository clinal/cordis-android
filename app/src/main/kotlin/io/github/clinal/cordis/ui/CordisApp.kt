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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clinal.cordis.domain.CordisInstance
import io.github.clinal.cordis.domain.RuntimeStatus

@Composable
fun CordisApp(viewModel: CordisViewModel = viewModel()) {
    val instances by viewModel.instances.collectAsState()
    val defaultInstance = instances.firstOrNull { it.id == "default" }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Cordis",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Android runtime console",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { viewModel.start() },
                    enabled = defaultInstance?.status?.canStart == true,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Start")
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(instances, key = { it.id }) { instance ->
                    InstancePanel(
                        instance = instance,
                        onStart = { viewModel.start(instance.id) },
                        onStop = { viewModel.stop(instance.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstancePanel(
    instance: CordisInstance,
    onStart: () -> Unit,
    onStop: () -> Unit,
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
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop ${instance.name}")
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
