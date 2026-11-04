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

    // ---- design v2 UI preferences

    /** "light" | "dark" | "auto" (default dark, as the design). */
    val themeMode: Flow<String> = safeData.map { it[KEY_THEME] ?: "dark" }.distinctUntilChanged()
    suspend fun setThemeMode(mode: String) { store.edit { it[KEY_THEME] = mode } }

    /** Engineer view: shows the detailed panels, replay, raw numbers and the run library actions. */
    val engineerView: Flow<Boolean> = safeData.map { it[KEY_ENGINEER] == "1" }.distinctUntilChanged()
    suspend fun setEngineerView(on: Boolean) { store.edit { it[KEY_ENGINEER] = if (on) "1" else "0" } }

    /** Test defaults: networks "dual" | "wifi" | "cellular"; length minutes (0 = no limit); movement walk|drive|rail|static. */
    val defaultNetworks: Flow<String> = safeData.map { it[KEY_NETWORKS] ?: "dual" }.distinctUntilChanged()
    suspend fun setDefaultNetworks(v: String) { store.edit { it[KEY_NETWORKS] = v } }
    val defaultLengthMin: Flow<Int> = safeData.map { it[KEY_LENGTH]?.toIntOrNull() ?: 10 }.distinctUntilChanged()
    suspend fun setDefaultLengthMin(v: Int) { store.edit { it[KEY_LENGTH] = v.toString() } }
    val defaultMovement: Flow<String> = safeData.map { it[KEY_MOVEMENT] ?: "walk" }.distinctUntilChanged()
    suspend fun setDefaultMovement(v: String) { store.edit { it[KEY_MOVEMENT] = v } }

    /** "Start tests on all companions". */
    val startCompanions: Flow<Boolean> = safeData.map { it[KEY_START_COMPANIONS] != "0" }.distinctUntilChanged()
    suspend fun setStartCompanions(on: Boolean) { store.edit { it[KEY_START_COMPANIONS] = if (on) "1" else "0" } }

    /** Welcome screen finished at least once. */
    val welcomed: Flow<Boolean> = safeData.map { it[KEY_WELCOMED] == "1" }.distinctUntilChanged()
    suspend fun setWelcomed() { store.edit { it[KEY_WELCOMED] = "1" } }

    // ---- email reports (auto step after each test)

    /** Open a pre-filled email with the report after each test (Android needs one tap on Send). */
    val autoEmail: Flow<Boolean> = safeData.map { it[KEY_AUTO_EMAIL] != "0" }.distinctUntilChanged()
    suspend fun setAutoEmail(on: Boolean) { store.edit { it[KEY_AUTO_EMAIL] = if (on) "1" else "0" } }

    /** Comma-separated recipients. */
    val emailRecipients: Flow<List<String>> = safeData.map { p ->
        (p[KEY_EMAIL_TO] ?: DEFAULT_EMAIL_TO).split(',', ';', ' ', '\n').map { it.trim() }.filter { it.contains('@') }
    }.distinctUntilChanged()
    suspend fun setEmailRecipients(list: List<String>) { store.edit { it[KEY_EMAIL_TO] = list.joinToString(",") } }

    /** Attach the full-data ZIP when it fits the mail size limit. */
    val emailZip: Flow<Boolean> = safeData.map { it[KEY_EMAIL_ZIP] != "0" }.distinctUntilChanged()
    suspend fun setEmailZip(on: Boolean) { store.edit { it[KEY_EMAIL_ZIP] = if (on) "1" else "0" } }

    /** Runs whose report email was already opened (so it happens once per test). */
    val emailedRuns: Flow<Set<String>> = safeData.map { (it[KEY_EMAILED] ?: "").split(',').filter { s -> s.isNotBlank() }.toSet() }
    suspend fun markEmailed(runId: String) {
        store.edit { p ->
            val cur = (p[KEY_EMAILED] ?: "").split(',').filter { it.isNotBlank() }
            p[KEY_EMAILED] = (cur.takeLast(199) + runId).distinct().joinToString(",")
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
        val KEY_THEME = stringPreferencesKey("ui_theme_mode")
        val KEY_ENGINEER = stringPreferencesKey("ui_engineer_view")
        val KEY_NETWORKS = stringPreferencesKey("ui_default_networks")
        val KEY_LENGTH = stringPreferencesKey("ui_default_length_min")
        val KEY_MOVEMENT = stringPreferencesKey("ui_default_movement")
        val KEY_START_COMPANIONS = stringPreferencesKey("ui_start_companions")
        val KEY_WELCOMED = stringPreferencesKey("ui_welcomed")
        val KEY_AUTO_EMAIL = stringPreferencesKey("email_auto")
        val KEY_EMAIL_TO = stringPreferencesKey("email_to")
        val KEY_EMAIL_ZIP = stringPreferencesKey("email_zip")
        val KEY_EMAILED = stringPreferencesKey("email_done_runs")
        const val DEFAULT_EMAIL_TO = "daniel.alphonsus@etihadrail.ae,eric.artau@etihadrail.ae"
    }
}
