package app.tracer.ui.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tracer.core.config.RunSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One DataStore instance per process (the delegate guarantees it). */
private val Context.tracerSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "tracer_settings")

/**
 * Persisted [RunSettings] (key "run_settings_json" = RunSettings.toStorageJson()), the debug
 * simulate-scenario choice (key "debug_sim_scenario") and the last pre-flight site (key
 * "last_site"). Shared with the live/replay UI.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<Preferences> get() = context.tracerSettingsStore

    private val safeData: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) {
            Log.w(TAG, "settings read failed; using defaults", e)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }

    val settings: Flow<RunSettings> = safeData.map { decode(it[KEY_SETTINGS]) }.distinctUntilChanged()

    /** Atomic read-modify-write. */
    suspend fun update(transform: (RunSettings) -> RunSettings) {
        store.edit { prefs -> prefs[KEY_SETTINGS] = transform(decode(prefs[KEY_SETTINGS])).toStorageJson() }
    }

    /** Debug "Simulate run" scenario name (one of Scenarios.names), or null when off. */
    val debugSimScenario: Flow<String?> = safeData.map { it[KEY_SIM]?.takeIf { s -> s.isNotBlank() } }.distinctUntilChanged()

    suspend fun setDebugSimScenario(name: String?) {
        store.edit { prefs ->
            if (name.isNullOrBlank()) {
                prefs.remove(KEY_SIM)
            } else {
                prefs[KEY_SIM] = name
            }
            Unit
        }
    }

    /** Site typed in the last pre-flight ("Office"), or null. */
    val lastSite: Flow<String?> = safeData.map { it[KEY_LAST_SITE]?.takeIf { s -> s.isNotBlank() } }.distinctUntilChanged()

    suspend fun setLastSite(site: String?) {
        store.edit { prefs ->
            val v = site?.trim()
            if (v.isNullOrEmpty()) prefs.remove(KEY_LAST_SITE) else prefs[KEY_LAST_SITE] = v
            Unit
        }
    }

    private fun decode(json: String?): RunSettings =
        json?.let { runCatching { RunSettings.fromJson(it) }.onFailure { e -> Log.w(TAG, "corrupt settings json", e) }.getOrNull() }
            ?: RunSettings()

    private companion object {
        const val TAG = "SettingsRepository"
        val KEY_SETTINGS = stringPreferencesKey("run_settings_json")
        val KEY_SIM = stringPreferencesKey("debug_sim_scenario")
        val KEY_LAST_SITE = stringPreferencesKey("last_site")
    }
}
