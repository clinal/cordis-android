package io.github.clinal.cordis.domain

data class CordisInstance(
    val id: String,
    val name: String,
    val port: Int,
    val dns: String,
    val status: RuntimeStatus,
    val bridgeStatus: AndroidBridgeStatus,
    val bridgeButtons: List<CordisButton>,
    val lastLogLines: List<String>,
)

enum class AndroidBridgeStatus {
    Stopped,
    WaitingForPlugin,
    Connected,
}

data class CordisButton(
    val id: String,
    val label: String,
    val icon: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
)

data class HomeShortcut(
    val instanceId: String,
    val buttonId: String,
    val title: String? = null,
    val icon: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
    val sort: Int,
)
