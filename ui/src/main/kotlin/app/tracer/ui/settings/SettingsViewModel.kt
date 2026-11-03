package app.tracer.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.config.ProbeEndpoint
import app.tracer.core.config.RunSettings
import app.tracer.core.config.Thresholds
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.companion.CompanionDevices
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    runs: RunRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /** null until the first DataStore read completes. */
    val settings: StateFlow<RunSettings?> = repository.settings.map<RunSettings, RunSettings?> { it }.state(null)
    val simScenario: StateFlow<String?> = repository.debugSimScenario.state(null)
    val themeMode: StateFlow<String> = repository.themeMode.state("dark")
    val engineerView: StateFlow<Boolean> = repository.engineerView.state(false)
    val defaultNetworks: StateFlow<String> = repository.defaultNetworks.state("dual")
    val defaultLengthMin: StateFlow<Int> = repository.defaultLengthMin.state(10)
    val defaultMovement: StateFlow<String> = repository.defaultMovement.state("walk")

    /** Companion phones seen in imported / linked runs. */
    val linkedDevices: StateFlow<Int> = runs.observeRuns().map { list -> CompanionDevices.from(list.map { it.run }).size }.state(0)

    val isDebugBuild: Boolean = 0 != (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)

    val versionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "—"

    fun update(transform: (RunSettings) -> RunSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun setSimScenario(name: String?) { viewModelScope.launch { repository.setDebugSimScenario(name) } }
    fun setThemeMode(mode: String) { viewModelScope.launch { repository.setThemeMode(mode) } }
    fun setEngineerView(on: Boolean) { viewModelScope.launch { repository.setEngineerView(on) } }
    fun setDefaultNetworks(v: String) { viewModelScope.launch { repository.setDefaultNetworks(v) } }
    fun setDefaultLengthMin(v: Int) { viewModelScope.launch { repository.setDefaultLengthMin(v) } }
    fun setDefaultMovement(v: String) { viewModelScope.launch { repository.setDefaultMovement(v) } }

    /** @return an error to show, or null when added. */
    fun addEndpoint(url: String, label: String): String? {
        val u = url.trim()
        if (!(u.startsWith("https://") || u.startsWith("http://"))) return "Enter an http(s) URL"
        val l = label.trim().ifEmpty { u }
        update { s -> if (s.endpoints.any { it.url == u }) s else s.copy(endpoints = s.endpoints + ProbeEndpoint(u, l)) }
        return null
    }

    fun removeEndpoint(url: String) {
        update { s -> s.copy(endpoints = s.endpoints.filterNot { it.url == url }.ifEmpty { s.endpoints }) }
    }

    /** Validates and applies thresholds JSON; returns an error to show, or null on success. */
    fun applyThresholds(text: String): String? {
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject ?: return "Thresholds must be a JSON object"
        } catch (e: Exception) {
            return "Invalid JSON: ${e.message ?: e.javaClass.simpleName}"
        }
        val unknown = obj.keys - DEFAULT_THRESHOLD_KEYS
        if (unknown.isNotEmpty()) return "Unknown keys: ${unknown.sorted().joinToString()}"
        val parsed = try {
            Thresholds.fromJson(obj)
        } catch (e: Exception) {
            return "Invalid JSON: ${e.message ?: e.javaClass.simpleName}"
        }
        update { it.copy(thresholds = parsed) }
        return null
    }

    fun resetThresholds() = update { it.copy(thresholds = Thresholds()) }

    companion object {
        private val DEFAULT_THRESHOLD_KEYS: Set<String> = Thresholds().toJson().keys
        private val PrettyJson = Json { prettyPrint = true }

        fun prettyThresholds(t: Thresholds): String = PrettyJson.encodeToString(JsonObject.serializer(), t.toJson())
    }
}
