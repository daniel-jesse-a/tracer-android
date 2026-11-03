package app.tracer.ui.transfer

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.RunRecord
import app.tracer.core.runtime.BundleService
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

data class ManifestFile(val path: String, val bytes: Long, val rows: Long?)

data class TransferUi(
    val run: RunRecord? = null,
    /** 0..1 while exporting */
    val progress: Float? = null,
    val file: File? = null,
    val manifest: List<ManifestFile> = emptyList(),
    val error: String? = null,
    val uploadUrl: String? = null,
    val uploading: Boolean = false,
    /** HTTP status of the last upload, or -1 on I/O failure */
    val uploadStatus: Int? = null,
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: RunRepository,
    private val bundles: BundleService,
    private val settings: SettingsRepository,
) : ViewModel() {

    val runId: String = checkNotNull(savedState.get<String>("runId")) { "transfer route needs runId" }
    val authority: String get() = bundles.fileProviderAuthority

    private val _ui = MutableStateFlow(TransferUi())
    val ui: StateFlow<TransferUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val run = runCatching { repository.getRun(runId) }.getOrNull()
            val url = settings.settings.first().uploadUrl?.takeIf { it.isNotBlank() }
            _ui.update { it.copy(run = run, uploadUrl = url) }
            export()
        }
    }

    fun export() {
        if (_ui.value.progress != null) return
        viewModelScope.launch {
            _ui.update { it.copy(progress = 0f, error = null, file = null, manifest = emptyList(), uploadStatus = null) }
            try {
                val file = bundles.export(runId) { p -> _ui.update { it.copy(progress = p.coerceIn(0f, 1f)) } }
                val manifest = withContext(Dispatchers.IO) { readManifest(file) }
                _ui.update { it.copy(progress = null, file = file, manifest = manifest) }
            } catch (e: Exception) {
                Log.e(TAG, "export failed", e)
                _ui.update { it.copy(progress = null, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun upload() {
        val file = _ui.value.file ?: return
        val url = _ui.value.uploadUrl ?: return
        if (_ui.value.uploading) return
        viewModelScope.launch {
            _ui.update { it.copy(uploading = true, uploadStatus = null) }
            val status = try {
                bundles.upload(file, url)
            } catch (e: Exception) {
                Log.w(TAG, "upload failed", e)
                -1
            }
            _ui.update { it.copy(uploading = false, uploadStatus = status) }
        }
    }

    private fun readManifest(file: File): List<ManifestFile> {
        val json = ZipFile(file).use { zip ->
            val entry = zip.getEntry("run.json") ?: return emptyList()
            zip.getInputStream(entry).use { it.readBytes().decodeToString() }
        }
        val files = Json.parseToJsonElement(json).jsonObject["files"] as? JsonArray ?: return emptyList()
        return files.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ManifestFile(path, (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L, (o["rows"] as? JsonPrimitive)?.longOrNull)
        }
    }

    private companion object { const val TAG = "Transfer" }
}
