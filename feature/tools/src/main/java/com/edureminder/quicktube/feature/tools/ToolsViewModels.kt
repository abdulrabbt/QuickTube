package com.edureminder.quicktube.feature.tools

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.media.PlayItem
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadStatus
import com.edureminder.quicktube.core.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- Recycle bin ----------

class RecycleBinViewModel(private val library: LibraryRepository) : ViewModel() {
    val items = library.trash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun restore(ids: List<Long>) = viewModelScope.launch { library.restore(ids) }
    fun deleteForever(ids: List<Long>) = viewModelScope.launch { library.deleteForever(ids) }
}

// ---------- Vault ----------

private typealias PinHash = com.edureminder.quicktube.core.common.PinHash

class VaultViewModel(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val player: PlayerController,
) : ViewModel() {
    val items = library.downloads.map { l -> l.filter { it.inVault && it.status == DownloadStatus.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pin = settings.settings.map { it.vaultPin }.stateIn(viewModelScope, SharingStarted.Eagerly, "loading")
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    fun setPin(p: String) = viewModelScope.launch { settings.update { it.copy(vaultPin = PinHash.hash(p)) }; _unlocked.value = true }
    fun tryUnlock(p: String): Boolean = PinHash.matches(p, pin.value).also { ok ->
        if (!ok) return@also
        _unlocked.value = true
        // A PIN saved before hashing is converted now that it's known.
        if (!PinHash.isHashed(pin.value.orEmpty())) viewModelScope.launch { settings.update { it.copy(vaultPin = PinHash.hash(p)) } }
    }
    fun lock() { _unlocked.value = false }
    fun unlockItems(ids: List<Long>) = viewModelScope.launch { library.setVault(ids, false) }
    fun delete(ids: List<Long>) = viewModelScope.launch { library.moveToTrash(ids) }
    fun play(task: DownloadTask) {
        val list = items.value.filter { it.type == task.type }
        player.play(list.map {
            PlayItem("dl:${it.id}", File(it.filePath).toURI().toString(), it.title, it.sourceHost.orEmpty(), it.thumbnail, it.type == ContentType.VIDEO)
        }, list.indexOf(task).coerceAtLeast(0))
    }
}

// ---------- WhatsApp status saver ----------

data class StatusFile(val name: String, val uri: Uri, val isVideo: Boolean, val modified: Long, val size: Long)

class StatusSaverViewModel(
    private val app: Application,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) : AndroidViewModel(app) {
    private val _statuses = MutableStateFlow<List<StatusFile>>(emptyList())
    val statuses: StateFlow<List<StatusFile>> = _statuses
    private val _needsAccess = MutableStateFlow(false)
    val needsAccess: StateFlow<Boolean> = _needsAccess
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val prefs = app.getSharedPreferences("status_saver", 0)

    /** Folder URIs WhatsApp (and WhatsApp Business) keep statuses in on Android 11+. */
    val initialTreeUri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2F.Statuses",
    )

    fun refresh() = viewModelScope.launch {
        _loading.value = true
        val list = withContext(Dispatchers.IO) {
            val found = mutableListOf<StatusFile>()
            if (Build.VERSION.SDK_INT >= 30) {
                val saved = prefs.getString("tree", null)?.let(Uri::parse)
                val granted = saved != null && app.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isReadPermission }
                if (!granted) { _needsAccess.value = true; return@withContext emptyList() }
                _needsAccess.value = false
                DocumentFile.fromTreeUri(app, saved!!)?.listFiles()?.forEach { f ->
                    val n = f.name ?: return@forEach
                    if (n.startsWith(".")) return@forEach
                    val video = n.endsWith(".mp4", true)
                    if (video || n.endsWith(".jpg", true) || n.endsWith(".png", true) || n.endsWith(".webp", true))
                        found += StatusFile(n, f.uri, video, f.lastModified(), f.length())
                }
            } else {
                listOf("WhatsApp/Media/.Statuses", "Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "WhatsApp Business/Media/.Statuses")
                    .map { File(Environment.getExternalStorageDirectory(), it) }
                    .flatMap { it.listFiles()?.toList().orEmpty() }
                    .filter { !it.name.startsWith(".") }
                    .forEach { f -> found += StatusFile(f.name, Uri.fromFile(f), f.name.endsWith(".mp4", true), f.lastModified(), f.length()) }
            }
            found.sortedByDescending { it.modified }
        }
        _statuses.value = list
        _loading.value = false
    }

    fun onTreeGranted(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString("tree", uri.toString()).apply()
        refresh()
    }

    fun save(file: StatusFile, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(settings.current().downloadDir, "Status").apply { mkdirs() }
                val target = File(dir, file.name)
                app.contentResolver.openInputStream(file.uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                android.media.MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), null, null)
                val mime = if (file.isVideo) "video/mp4" else "image/jpeg"
                library.addLocalFile(
                    DownloadTask(
                        title = "Status " + file.name.substringBeforeLast('.'), pageUrl = null, sourceHost = "WhatsApp", thumbnail = null,
                        url = file.uri.toString(), audioUrl = null, filePath = target.absolutePath, mimeType = mime,
                        type = ContentType.fromMime(mime), quality = null, totalBytes = target.length(), downloadedBytes = target.length(),
                        status = DownloadStatus.COMPLETED, completedAt = System.currentTimeMillis(),
                    ),
                )
            }.isSuccess
        }
        done(ok)
    }
}
