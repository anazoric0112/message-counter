package com.porukecounter.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.porukecounter.core.AnalysisConfig
import com.porukecounter.core.AnalysisResult
import com.porukecounter.core.ChatAnalyzer
import com.porukecounter.core.CountMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

data class ChatFile(val uri: Uri, val name: String)
data class AppState(
    val files: List<ChatFile> = emptyList(),
    val result: AnalysisResult? = null,
    val busy: Boolean = false,
    val status: String = "No files selected",
)

class AppModel(application: Application) : AndroidViewModel(application) {
    val preferences = application.getSharedPreferences("poruke", 0)
    private val resolver = application.contentResolver
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private var analysisJob: Job? = null
    var exportRows: List<List<String>> = emptyList()
    var pendingBitmap: Bitmap? = null

    init {
        val restored = runCatching {
            val saved = JSONArray(preferences.getString("files", "[]"))
            (0 until saved.length()).map { index ->
                val uri = Uri.parse(saved.getString(index))
                ChatFile(uri, displayName(uri))
            }
        }.getOrDefault(emptyList())
        mutableState.value = AppState(files = restored, status = if (restored.isEmpty()) "No files selected" else "${restored.size} files selected")
    }

    fun savedConfig(): AnalysisConfig = AnalysisConfig(
        preferences.getString("names", "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() },
        preferences.getString("start", "0.0")!!,
        preferences.getString("end", "12.99")!!,
        CountMode.valueOf(preferences.getString("mode", "MESSAGES")!!),
    )

    fun saveConfig(config: AnalysisConfig) {
        preferences.edit().putString("names", config.names.joinToString("\n"))
            .putString("start", config.startMonth).putString("end", config.endMonth)
            .putString("mode", config.countMode.name).remove("rules").apply()
    }

    fun addFiles(uris: List<Uri>) {
        if (state.value.busy) return
        val added = uris.map { uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ChatFile(uri, displayName(uri))
        }
        val files = (state.value.files + added).distinctBy { it.uri }
        updateFiles(files)
    }

    fun removeFile(file: ChatFile) {
        if (state.value.busy) return
        runCatching { resolver.releasePersistableUriPermission(file.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        updateFiles(state.value.files.filterNot { it.uri == file.uri })
    }

    private fun updateFiles(files: List<ChatFile>) {
        preferences.edit().putString("files", JSONArray(files.map { it.uri.toString() }).toString()).apply()
        mutableState.value = AppState(files = files, status = "${files.size} files selected")
    }

    fun analyze(config: AnalysisConfig) {
        config.validate()
        require(state.value.files.isNotEmpty()) { "Select at least one WhatsApp text export." }
        if (state.value.busy) return
        saveConfig(config)
        val files = state.value.files
        mutableState.value = state.value.copy(busy = true, result = null, status = "Analyzing...")
        analysisJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val analyzer = ChatAnalyzer(config)
                    val jobContext = coroutineContext
                    for (file in files) {
                        jobContext.ensureActive()
                        val input = resolver.openInputStream(file.uri) ?: error("Cannot open ${file.name}")
                        input.bufferedReader(Charsets.UTF_8).use { reader ->
                            analyzer.consume(file.name, reader.lineSequence().onEach { jobContext.ensureActive() })
                        }
                    }
                    analyzer.result()
                }
                mutableState.value = state.value.copy(
                    result = result, busy = false,
                    status = if (result.stats.headers == 0L) "No compatible message headers found" else
                        "${result.stats.headers} headers | ${result.rangeTotal} in range | ${result.total} participant total",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                mutableState.value = state.value.copy(busy = false, status = "Analysis cancelled")
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = state.value.copy(busy = false, status = error.message ?: "Unable to analyze files")
            }
        }
    }

    fun cancel() {
        analysisJob?.cancel()
    }

    override fun onCleared() {
        pendingBitmap?.recycle()
        super.onCleared()
    }

    private fun displayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Chat export"
}