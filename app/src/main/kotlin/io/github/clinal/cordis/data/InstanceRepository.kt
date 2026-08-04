package io.github.clinal.cordis.data

import android.content.Context
import io.github.clinal.cordis.domain.AndroidBridgeStatus
import io.github.clinal.cordis.domain.CordisInstance
import io.github.clinal.cordis.domain.CordisButton
import io.github.clinal.cordis.domain.HomeShortcut
import io.github.clinal.cordis.domain.RuntimeStatus
import io.github.clinal.cordis.runtime.RuntimePaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

class InstanceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val paths = RuntimePaths(appContext)

    private val mutableInstances = MutableStateFlow(loadInstances())
    val instances: StateFlow<List<CordisInstance>> = mutableInstances
    private val mutableHomeShortcuts = MutableStateFlow(loadHomeShortcuts())
    val homeShortcuts: StateFlow<List<HomeShortcut>> = mutableHomeShortcuts

    @Synchronized
    fun addInstance(
        name: String? = null,
        hasWebService: Boolean = true,
        patchPort: Boolean = hasWebService,
        startCommand: String = DEFAULT_START_COMMAND,
    ): CordisInstance {
        val nextIndex = nextInstanceIndex()
        val id = instanceId(nextIndex)
        val currentInstances = mutableInstances.value
        val instance = CordisInstance(
            id = id,
            name = name?.trim().takeUnless { it.isNullOrEmpty() } ?: "instance $nextIndex",
            port = nextAvailablePort(currentInstances),
            dns = DEFAULT_DNS,
            androidControlEnabled = false,
            hasWebService = hasWebService,
            patchPort = hasWebService && patchPort,
            startCommand = sanitizeStartCommand(startCommand),
            status = initialStatus(),
            bridgeStatus = AndroidBridgeStatus.Stopped,
            bridgeButtons = emptyList(),
            lastLogLines = listOf("Press Start to prepare the Cordis runtime."),
        )

        preferences.edit().putStringSet(KEY_INSTANCE_IDS, currentIds() + id).apply()
        paths.instanceHome(id).mkdirs()
        saveInstanceConfig(instance)
        mutableInstances.update { instances ->
            instances + instance
        }
        return instance
    }

    fun removeInstance(id: String) {
        preferences.edit().putStringSet(KEY_INSTANCE_IDS, currentIds() - id).apply()
        clearInstanceConfig(id)
        paths.instanceHome(id).deleteRecursively()
        saveHomeShortcuts(mutableHomeShortcuts.value.filterNot { it.instanceId == id })
        mutableInstances.update { instances ->
            instances.filterNot { it.id == id }
        }
    }

    fun updateInstanceConfig(
        id: String,
        name: String,
        port: Int,
        dns: String,
        androidControlEnabled: Boolean,
        hasWebService: Boolean,
        patchPort: Boolean,
        startCommand: String,
    ) {
        val sanitizedName = name.trim().ifBlank { defaultName(id) }
        val sanitizedPort = port.coerceIn(MIN_PORT, MAX_PORT)
        val sanitizedDns = dns.trim().ifBlank { DEFAULT_DNS }
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id == id) {
                    instance.copy(
                        name = sanitizedName,
                        port = sanitizedPort,
                        dns = sanitizedDns,
                        androidControlEnabled = androidControlEnabled,
                        hasWebService = hasWebService,
                        patchPort = hasWebService && patchPort,
                        startCommand = sanitizeStartCommand(startCommand),
                    )
                        .also(::saveInstanceConfig)
                } else {
                    instance
                }
            }
        }
    }

    fun instance(id: String): CordisInstance? = mutableInstances.value.firstOrNull { it.id == id }

    fun autoStartInstanceIds(): List<String> {
        return mutableInstances.value
            .filter { instance -> preferences.getBoolean(instanceKey(instance.id, KEY_AUTO_START), false) }
            .map(CordisInstance::id)
    }

    fun setAutoStart(id: String, enabled: Boolean) {
        preferences.edit()
            .putBoolean(instanceKey(id, KEY_AUTO_START), enabled)
            .apply()
    }

    fun updateStatus(id: String, status: RuntimeStatus, logLine: String? = null) {
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id != id) {
                    instance
                } else {
                    instance.copy(
                        status = status,
                        bridgeStatus = if (status == RuntimeStatus.Running) {
                            instance.bridgeStatus
                        } else {
                            AndroidBridgeStatus.Stopped
                        },
                        bridgeButtons = if (status == RuntimeStatus.Running) {
                            instance.bridgeButtons
                        } else {
                            emptyList()
                        },
                        lastLogLines = appendLog(instance.lastLogLines, logLine),
                    )
                }
            }
        }
    }

    fun appendLog(id: String, line: String) {
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id == id) {
                    instance.copy(lastLogLines = appendLog(instance.lastLogLines, line))
                } else {
                    instance
                }
            }
        }
    }

    fun updateBridgeStatus(id: String, status: AndroidBridgeStatus) {
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id == id) {
                    instance.copy(
                        bridgeStatus = status,
                        bridgeButtons = if (status == AndroidBridgeStatus.Stopped) emptyList() else instance.bridgeButtons,
                    )
                } else {
                    instance
                }
            }
        }
    }

    fun replaceBridgeButtons(id: String, buttons: List<CordisButton>) {
        val sortedButtons = buttons.sortedBy { it.label }
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id == id) instance.copy(bridgeButtons = sortedButtons) else instance
            }
        }
    }

    fun registerBridgeButton(id: String, button: CordisButton) {
        refreshHomeShortcut(id, button)
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id != id) {
                    instance
                } else {
                    val buttons = (instance.bridgeButtons.filterNot { it.id == button.id } + button).sortedBy { it.label }
                    instance.copy(bridgeButtons = buttons)
                }
            }
        }
    }

    fun patchBridgeButton(id: String, buttonId: String, patch: ButtonPatch) {
        var patchedButton: CordisButton? = null
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id != id) {
                    instance
                } else {
                    instance.copy(
                        bridgeButtons = instance.bridgeButtons.map { button ->
                            if (button.id == buttonId) {
                                patch.applyTo(button).also { patchedButton = it }
                            } else {
                                button
                            }
                        },
                    )
                }
            }
        }
        patchedButton?.let { refreshHomeShortcut(id, it) }
    }

    fun unregisterBridgeButton(id: String, buttonId: String) {
        mutableInstances.update { instances ->
            instances.map { instance ->
                if (instance.id == id) {
                    instance.copy(bridgeButtons = instance.bridgeButtons.filterNot { it.id == buttonId })
                } else {
                    instance
                }
            }
        }
    }

    fun addHomeShortcut(instanceId: String, button: CordisButton) {
        val buttonId = button.id
        if (mutableHomeShortcuts.value.any { it.instanceId == instanceId && it.buttonId == buttonId }) return
        val nextSort = (mutableHomeShortcuts.value.maxOfOrNull(HomeShortcut::sort) ?: 0) + 1
        saveHomeShortcuts(mutableHomeShortcuts.value + button.toHomeShortcut(instanceId, nextSort))
    }

    fun removeHomeShortcut(instanceId: String, buttonId: String) {
        saveHomeShortcuts(mutableHomeShortcuts.value.filterNot { it.instanceId == instanceId && it.buttonId == buttonId })
    }

    private fun initialStatus(): RuntimeStatus {
        return RuntimeStatus.Stopped
    }

    private fun loadInstances(): List<CordisInstance> {
        val ids = currentIds().sortedWith(compareBy(::instanceSortIndex, { it }))
        return ids.mapIndexed { index, id ->
            paths.instanceHome(id).mkdirs()
            CordisInstance(
                id = id,
                name = preferences.getString(instanceKey(id, KEY_NAME), null) ?: defaultName(id),
                port = preferences.getInt(instanceKey(id, KEY_PORT), DEFAULT_BASE_PORT + index)
                    .coerceIn(MIN_PORT, MAX_PORT),
                dns = preferences.getString(instanceKey(id, KEY_DNS), null)
                    ?.trim()
                    ?.ifBlank { DEFAULT_DNS }
                    ?: DEFAULT_DNS,
                androidControlEnabled = preferences.getBoolean(instanceKey(id, KEY_ANDROID_CONTROL), false),
                hasWebService = preferences.getBoolean(instanceKey(id, KEY_HAS_WEB_SERVICE), true),
                patchPort = preferences.getBoolean(instanceKey(id, KEY_HAS_WEB_SERVICE), true) &&
                    preferences.getBoolean(instanceKey(id, KEY_PATCH_PORT), true),
                startCommand = sanitizeStartCommand(
                    preferences.getString(instanceKey(id, KEY_START_COMMAND), null),
                ),
                status = initialStatus(),
                bridgeStatus = AndroidBridgeStatus.Stopped,
                bridgeButtons = emptyList(),
                lastLogLines = listOf("Press Start to prepare the Cordis runtime."),
            )
        }
    }

    private fun loadHomeShortcuts(): List<HomeShortcut> {
        val json = preferences.getString(KEY_HOME_BUTTONS, null)?.takeIf(String::isNotBlank) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                HomeShortcut(
                    instanceId = item.getString("instanceId"),
                    buttonId = item.getString("buttonId"),
                    title = item.optString("title").takeIf(String::isNotBlank),
                    icon = item.optString("icon").takeIf(String::isNotBlank),
                    description = item.optString("description").takeIf(String::isNotBlank),
                    enabled = if (item.has("enabled")) item.optBoolean("enabled") else true,
                    disabledReason = item.optString("disabledReason").takeIf(String::isNotBlank),
                    sort = item.optInt("sort", index),
                )
            }.sortedBy(HomeShortcut::sort)
        }.getOrElse { emptyList() }
    }

    private fun saveHomeShortcuts(homeShortcuts: List<HomeShortcut>) {
        val sorted = homeShortcuts.sortedBy(HomeShortcut::sort)
        val array = JSONArray()
        sorted.forEach { homeShortcut ->
            array.put(
                JSONObject()
                    .put("instanceId", homeShortcut.instanceId)
                    .put("buttonId", homeShortcut.buttonId)
                    .put("title", homeShortcut.title ?: "")
                    .put("icon", homeShortcut.icon ?: "")
                    .put("description", homeShortcut.description ?: "")
                    .put("enabled", homeShortcut.enabled)
                    .put("disabledReason", homeShortcut.disabledReason ?: "")
                    .put("sort", homeShortcut.sort),
            )
        }
        preferences.edit().putString(KEY_HOME_BUTTONS, array.toString()).apply()
        mutableHomeShortcuts.value = sorted
    }

    private fun defaultName(id: String): String {
        return "instance ${instanceSortIndex(id)}"
    }

    private fun currentIds(): Set<String> {
        return preferences.getStringSet(KEY_INSTANCE_IDS, emptySet()).orEmpty() - DEFAULT_INSTANCE_ID
    }

    private fun nextInstanceIndex(): Int {
        val used = currentIds().map(::instanceSortIndex).toSet()
        return generateSequence(1) { it + 1 }.first { it !in used }
    }

    private fun instanceSortIndex(id: String): Int {
        return id.removePrefix(INSTANCE_ID_PREFIX).toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun instanceId(index: Int): String = "$INSTANCE_ID_PREFIX$index"

    private fun nextAvailablePort(instances: List<CordisInstance>): Int {
        val used = instances.map(CordisInstance::port).toSet()
        return generateSequence(DEFAULT_BASE_PORT) { it + 1 }
            .first { it in MIN_PORT..MAX_PORT && it !in used }
    }

    private fun saveInstanceConfig(instance: CordisInstance) {
        preferences.edit()
            .putString(instanceKey(instance.id, KEY_NAME), instance.name)
            .putInt(instanceKey(instance.id, KEY_PORT), instance.port)
            .putString(instanceKey(instance.id, KEY_DNS), instance.dns)
            .putBoolean(instanceKey(instance.id, KEY_ANDROID_CONTROL), instance.androidControlEnabled)
            .putBoolean(instanceKey(instance.id, KEY_HAS_WEB_SERVICE), instance.hasWebService)
            .putBoolean(instanceKey(instance.id, KEY_PATCH_PORT), instance.hasWebService && instance.patchPort)
            .putString(instanceKey(instance.id, KEY_START_COMMAND), instance.startCommand)
            .apply()
    }

    private fun clearInstanceConfig(id: String) {
        preferences.edit()
            .remove(instanceKey(id, KEY_NAME))
            .remove(instanceKey(id, KEY_PORT))
            .remove(instanceKey(id, KEY_DNS))
            .remove(instanceKey(id, KEY_AUTO_START))
            .remove(instanceKey(id, KEY_ANDROID_CONTROL))
            .remove(instanceKey(id, KEY_HAS_WEB_SERVICE))
            .remove(instanceKey(id, KEY_PATCH_PORT))
            .remove(instanceKey(id, KEY_START_COMMAND))
            .apply()
    }

    private fun instanceKey(id: String, key: String): String = "$id.$key"

    private fun sanitizeStartCommand(command: String?): String {
        return command?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_START_COMMAND
    }

    private fun appendLog(lines: List<String>, line: String?): List<String> {
        if (line.isNullOrBlank()) return lines
        return (lines + line).takeLast(MAX_LOG_LINES)
    }

    private fun refreshHomeShortcut(instanceId: String, button: CordisButton) {
        val shortcuts = mutableHomeShortcuts.value
        if (shortcuts.none { it.instanceId == instanceId && it.buttonId == button.id }) return
        saveHomeShortcuts(
            shortcuts.map { shortcut ->
                if (shortcut.instanceId == instanceId && shortcut.buttonId == button.id) {
                    button.toHomeShortcut(instanceId, shortcut.sort)
                } else {
                    shortcut
                }
            },
        )
    }

    private fun CordisButton.toHomeShortcut(instanceId: String, sort: Int): HomeShortcut {
        return HomeShortcut(
            instanceId = instanceId,
            buttonId = id,
            title = label,
            icon = icon,
            description = description,
            enabled = enabled,
            disabledReason = disabledReason,
            sort = sort,
        )
    }

    companion object {
        const val DEFAULT_INSTANCE_ID = "default"
        const val DEFAULT_BASE_PORT = 3140
        const val DEFAULT_DNS = "223.5.5.5"
        const val DEFAULT_START_COMMAND = "yarn start"
        private const val PREFERENCES_NAME = "cordis_instances"
        private const val KEY_INSTANCE_IDS = "instance_ids"
        private const val KEY_NAME = "name"
        private const val KEY_PORT = "port"
        private const val KEY_DNS = "dns"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_ANDROID_CONTROL = "android_control"
        private const val KEY_HAS_WEB_SERVICE = "has_web_service"
        private const val KEY_PATCH_PORT = "patch_port"
        private const val KEY_START_COMMAND = "start_command"
        private const val KEY_HOME_BUTTONS = "home_buttons"
        private const val INSTANCE_ID_PREFIX = "instance-"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val MAX_LOG_LINES = 200
    }
}

data class ButtonPatch(
    val label: String? = null,
    val icon: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    val disabledReason: String? = null,
) {
    fun applyTo(button: CordisButton): CordisButton {
        return button.copy(
            label = label ?: button.label,
            icon = icon ?: button.icon,
            description = description ?: button.description,
            enabled = enabled ?: button.enabled,
            disabledReason = disabledReason ?: button.disabledReason,
        )
    }
}
