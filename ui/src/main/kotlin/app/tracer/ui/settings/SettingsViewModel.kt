package app.tracer.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.config.ProbeEndpoint
import app.tracer.core.config.RunSettings
import app.tracer.core.config.Thresholds
import app.tracer.ui.R
import app.tracer.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    /** null until the first DataStore read completes. */
    val settings: StateFlow<RunSettings?> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val simScenario: StateFlow<String?> =
        repository.debugSimScenario.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isDebugBuild: Boolean = 0 != (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)

    fun update(transform: (RunSettings) -> RunSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun setSimScenario(name: String?) {
        viewModelScope.launch { repository.setDebugSimScenario(name) }
    }

    fun addEndpoint(url: String, label: String): UiText? {
        val u = url.trim()
        if (!(u.startsWith("https://") || u.startsWith("http://"))) return UiText.Res(R.string.settings_endpoint_invalid)
        val l = label.trim().ifEmpty { u }
        update { s -> if (s.endpoints.any { it.url == u }) s else s.copy(endpoints = s.endpoints + ProbeEndpoint(u, l)) }
        return null
    }

    fun removeEndpoint(url: String) {
        update { s -> s.copy(endpoints = s.endpoints.filterNot { it.url == url }.ifEmpty { s.endpoints }) }
    }

    /** Validates and applies thresholds JSON; returns an error to show, or null on success. */
    fun applyThresholds(text: String): UiText? {
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject ?: return UiText.Res(R.string.settings_thresholds_not_object)
        } catch (e: Exception) {
            return UiText.Res(R.string.settings_thresholds_invalid_json, listOf(e.message ?: e.javaClass.simpleName))
        }
        val unknown = obj.keys - DEFAULT_THRESHOLD_KEYS
        if (unknown.isNotEmpty()) return UiText.Res(R.string.settings_thresholds_unknown_keys, listOf(unknown.sorted().joinToString()))
        val parsed = try {
            Thresholds.fromJson(obj)
        } catch (e: Exception) {
            return UiText.Res(R.string.settings_thresholds_invalid_json, listOf(e.message ?: e.javaClass.simpleName))
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
