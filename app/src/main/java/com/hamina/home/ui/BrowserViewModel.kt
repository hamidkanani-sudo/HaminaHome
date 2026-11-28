package com.hamina.home.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hamina.home.HaminaApp
import com.hamina.home.data.local.LocalStorage
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.model.SortMode
import com.hamina.home.data.model.ViewMode
import com.hamina.home.data.playback.PlaybackRepository
import com.hamina.home.data.storage.TransferManager
import com.hamina.home.util.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialogs that can be shown over the browser. */
sealed class BrowserDialog {
    object Actions : BrowserDialog()
    class Rename(val item: MediaItem) : BrowserDialog()
    object NewFolder : BrowserDialog()
    class ConfirmDelete(val items: List<MediaItem>) : BrowserDialog()
    class Message(val text: String) : BrowserDialog()
}

/**
 * Holds all browsing state outside the Activity, so it survives configuration changes and so the
 * navigation rules (Back, Retry, focus restore) and file actions live in one place.
 */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val SECTION_SERVERS = 1
        const val SECTION_STORAGE = 2
    }

    private class Frame(val path: String, val focusIndex: Int)

    private val app get() = HaminaApp.instance

    // ---------- state shown by the UI ----------

    var servers by mutableStateOf<List<SmbServer>>(emptyList())
        private set
    var volumes by mutableStateOf<List<LocalStorage.Volume>>(emptyList())
        private set
    var recent by mutableStateOf<List<PlaybackRepository.RecentPlay>>(emptyList())
        private set
    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** The source (SMB server or storage volume) we are inside of; null = home screen. */
    var currentServer by mutableStateOf<SmbServer?>(null)
        private set
    var currentPath by mutableStateOf("")
        private set
    var sortMode by mutableStateOf(SortMode.NAME_ASC)
        private set
    var viewMode by mutableStateOf(ViewMode.LIST)
        private set

    /** On the home screen: clicking a server edits it instead of opening it. */
    var editMode by mutableStateOf(false)
        private set

    /** Index of the focused entry in whichever list is shown. Updated by the UI on focus changes. */
    var focusIndex by mutableIntStateOf(0)

    /** True while the UI should move focus to [focusIndex] (after loading, going back, ...). */
    var focusPending by mutableStateOf(false)
        private set

    /** Home screen: the row that should get focus when coming back (servers or storage). */
    var homeSection by mutableIntStateOf(SECTION_SERVERS)
        private set

    /** On the home screen, the first start focuses "Continue watching"; coming back focuses the source. */
    var focusRecentFirst by mutableStateOf(true)
        private set

    /** The home screen is ready once servers and recent items were read. */
    var homeReady by mutableStateOf(false)
        private set

    var showServerForm by mutableStateOf(false)
        private set
    var formServer by mutableStateOf<SmbServer?>(null)
        private set

    // ---------- file actions ----------

    var clipboard by mutableStateOf<List<TransferManager.ClipItem>>(emptyList())
        private set
    var clipboardMove by mutableStateOf(false)
        private set
    var marked by mutableStateOf<Set<String>>(emptySet())
        private set
    var dialog by mutableStateOf<BrowserDialog?>(null)
        private set

    /** Progress of the running copy / move / delete (shown at the top of the screen). */
    val transfer: TransferManager.TransferState? get() = app.transferManager.state

    val selectedItem: MediaItem?
        get() = if (currentServer != null) items.getOrNull(focusIndex) else null

    private val stack = ArrayDeque<Frame>()
    private var hasListing = false
    private var lastHomeIndex = 0
    private var loadJob: Job? = null
    private var retryAction: (() -> Unit)? = null
    private var serversLoaded = false
    private var recentLoaded = false

    init {
        viewModelScope.launch {
            app.serverRepository.servers.collect {
                servers = it
                serversLoaded = true
                updateReady()
            }
        }
        viewModelScope.launch {
            recent = app.playbackRepository.getRecent()
            recentLoaded = true
            updateReady()
        }
        viewModelScope.launch {
            app.settingsRepository.sortMode.collect { mode ->
                if (mode != sortMode) {
                    sortMode = mode
                    items = sortItems(items, mode)
                }
            }
        }
        viewModelScope.launch {
            app.settingsRepository.viewMode.collect { viewMode = it }
        }
        refreshVolumes()

        // When a copy/move/delete ends, reload the folder if it was affected.
        app.transferManager.onChanged = { affected ->
            val server = currentServer
            if (server != null && hasListing && !isLoading &&
                affected.any { it.first == server.id && it.second == currentPath }
            ) {
                refresh()
            }
        }
    }

    override fun onCleared() {
        app.transferManager.onChanged = null
        super.onCleared()
    }

    private fun updateReady() {
        if (!homeReady && serversLoaded && recentLoaded) {
            homeReady = true
            focusPending = true
        }
    }

    fun consumeFocus() {
        focusPending = false
    }

    fun refreshVolumes() {
        viewModelScope.launch {
            volumes = withContext(Dispatchers.IO) { LocalStorage.volumes(app) }
        }
    }

    // ---------- navigation ----------

    fun openServer(server: SmbServer, index: Int, section: Int = SECTION_SERVERS) {
        lastHomeIndex = index
        homeSection = section
        stack.clear()
        items = emptyList()
        marked = emptySet()
        hasListing = false
        currentServer = server
        currentPath = ""
        load(server, "", force = false, restoreIndex = 0, push = null, pop = false)
    }

    fun openFolder(folder: MediaItem, fromIndex: Int) {
        val server = currentServer ?: return
        load(
            server, folder.path, force = false, restoreIndex = 0,
            push = Frame(currentPath, fromIndex), pop = false
        )
    }

    /** Handles the Back key. Returns false when there is nothing to go back to (let the system exit). */
    fun goUp(): Boolean {
        val server = currentServer ?: return false

        if (isLoading) {
            // Cancel the pending load and stay where we were.
            loadJob?.cancel()
            isLoading = false
            retryAction = null
            if (!hasListing) leaveServer() else focusPending = true
            return true
        }
        if (errorMessage != null) {
            errorMessage = null
            retryAction = null
            if (!hasListing) leaveServer() else focusPending = true
            return true
        }
        val frame = stack.lastOrNull()
        if (frame == null) {
            leaveServer()
        } else {
            load(server, frame.path, force = false, restoreIndex = frame.focusIndex, push = null, pop = true)
        }
        return true
    }

    fun retry() {
        retryAction?.invoke()
    }

    /** Reloads the current folder from the source (bypasses the cache). */
    fun refresh() {
        val server = currentServer ?: return
        if (!hasListing) return
        load(server, currentPath, force = true, restoreIndex = focusIndex, push = null, pop = false)
    }

    private fun leaveServer() {
        loadJob?.cancel()
        currentServer = null
        currentPath = ""
        items = emptyList()
        marked = emptySet()
        hasListing = false
        isLoading = false
        errorMessage = null
        retryAction = null
        stack.clear()
        focusIndex = lastHomeIndex
        focusRecentFirst = false
        focusPending = true
        loadRecent()
        refreshVolumes()
    }

    private fun load(
        server: SmbServer,
        path: String,
        force: Boolean,
        restoreIndex: Int,
        push: Frame?,
        pop: Boolean
    ) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        // Retry repeats exactly this request (and bypasses the cache).
        retryAction = { load(server, path, true, restoreIndex, push, pop) }

        // The list comes first: thumbnail work waits until this listing is on screen.
        app.thumbnailManager.acquireBusy()
        val job = viewModelScope.launch {
            val result = app.smbManager.listDirectory(server, path, force)
            isLoading = false
            if (result.isSuccess) {
                val ordered = sortItems(decorate(result.getOrThrow()), sortMode)
                currentServer = server
                currentPath = path
                hasListing = true
                if (push != null) stack.addLast(push)
                if (pop) stack.removeLastOrNull()
                items = ordered
                marked = emptySet()
                focusIndex = restoreIndex.coerceIn(0, (ordered.size - 1).coerceAtLeast(0))
                focusPending = ordered.isNotEmpty()
            } else {
                errorMessage = result.exceptionOrNull()?.message ?: "Network error. Check connection."
            }
        }
        job.invokeOnCompletion { app.thumbnailManager.releaseBusy() }
        loadJob = job
    }

    // ---------- playback info ----------

    private fun loadRecent() {
        viewModelScope.launch { recent = app.playbackRepository.getRecent() }
    }

    /** Called when returning from the player so resume info and "Continue watching" are current. */
    fun refreshPlayback() {
        loadRecent()
        refreshVolumes()
        val snapshot = items
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val decorated = decorate(snapshot)
            // Ignore the result if a newer listing replaced the items meanwhile.
            if (items === snapshot) {
                items = if (sortMode == SortMode.LAST_PLAYED) sortItems(decorated, sortMode) else decorated
            }
        }
    }

    private suspend fun decorate(list: List<MediaItem>): List<MediaItem> {
        val playback = app.playbackRepository.getAll()
        if (playback.isEmpty()) return list
        return list.map { item ->
            val info = if (item.isDirectory) null else playback[item.id]
            if (info != null) {
                item.copy(lastPlayedPosition = info.positionMs, lastPlayedAt = info.lastPlayedAt)
            } else {
                item
            }
        }
    }

    // ---------- sorting / view ----------

    fun cycleSort() {
        val modes = SortMode.values()
        val next = modes[(modes.indexOf(sortMode) + 1) % modes.size]
        sortMode = next
        items = sortItems(items, next)
        viewModelScope.launch { app.settingsRepository.setSortMode(next) }
    }

    fun toggleViewMode() {
        val next = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        viewMode = next
        viewModelScope.launch { app.settingsRepository.setViewMode(next) }
    }

    private fun sortItems(list: List<MediaItem>, mode: SortMode): List<MediaItem> {
        val dirsFirst = compareBy<MediaItem> { !it.isDirectory }
        return when (mode) {
            SortMode.NAME_ASC -> list.sortedWith(
                dirsFirst.thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            )
            SortMode.NAME_DESC -> list.sortedWith(
                dirsFirst.thenComparator { a, b -> NaturalOrder.compare(b.name, a.name) }
            )
            SortMode.DATE_ASC -> list.sortedWith(dirsFirst.thenBy { it.lastModified })
            SortMode.DATE_DESC -> list.sortedWith(dirsFirst.thenByDescending { it.lastModified })
            SortMode.SIZE_ASC -> list.sortedWith(dirsFirst.thenBy { it.size })
            SortMode.SIZE_DESC -> list.sortedWith(dirsFirst.thenByDescending { it.size })
            SortMode.LAST_PLAYED -> list.sortedWith(dirsFirst.thenByDescending { it.lastPlayedAt })
        }
    }

    // ---------- file actions (copy, cut, paste, rename, delete, new folder) ----------

    /** The marked items, or the focused item when nothing is marked. */
    fun targetItems(): List<MediaItem> =
        if (marked.isNotEmpty()) items.filter { marked.contains(it.id) } else listOfNotNull(selectedItem)

    fun openActions() {
        if (currentServer != null && hasListing && !isLoading) dialog = BrowserDialog.Actions
    }

    fun dismissDialog() {
        dialog = null
        focusPending = true
    }

    fun showMessage(text: String) {
        dialog = BrowserDialog.Message(text)
    }

    fun toggleMark() {
        val item = selectedItem ?: return
        marked = if (marked.contains(item.id)) marked - item.id else marked + item.id
        dismissDialog()
    }

    fun clearMarks() {
        marked = emptySet()
        dismissDialog()
    }

    fun copyToClipboard(move: Boolean) {
        val server = currentServer ?: return
        val targets = targetItems()
        if (targets.isEmpty()) return
        clipboard = targets.map { TransferManager.ClipItem(server, it) }
        clipboardMove = move
        marked = emptySet()
        dismissDialog()
    }

    fun clearClipboard() {
        clipboard = emptyList()
        dismissDialog()
    }

    fun canPasteHere(): Boolean {
        val server = currentServer ?: return false
        if (clipboard.isEmpty() || !hasListing) return false
        // The list of shares of an SMB server is not a place where files can be put.
        return server.isLocal || server.share.isNotBlank() || currentPath.isNotEmpty()
    }

    fun paste() {
        val server = currentServer ?: return
        if (!canPasteHere()) {
            showMessage("Open a folder first. Files cannot be pasted into the list of shares.")
            return
        }
        app.transferManager.copy(clipboard, server, currentPath, clipboardMove)
        if (clipboardMove) clipboard = emptyList()
        dismissDialog()
    }

    fun askDelete() {
        val targets = targetItems()
        if (targets.isEmpty()) return
        dialog = BrowserDialog.ConfirmDelete(targets)
    }

    fun confirmDelete(targets: List<MediaItem>) {
        val server = currentServer ?: return
        app.transferManager.delete(targets.map { TransferManager.ClipItem(server, it) })
        marked = emptySet()
        dismissDialog()
    }

    fun askRename() {
        val item = targetItems().singleOrNull() ?: return
        dialog = BrowserDialog.Rename(item)
    }

    fun askNewFolder() {
        dialog = BrowserDialog.NewFolder
    }

    fun rename(item: MediaItem, newName: String) {
        val server = currentServer ?: return
        dismissDialog()
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    app.storageOps.rename(server, item.path, newName)
                    app.cacheManager.invalidateFolder(server.id, currentPath)
                    null
                } catch (e: Exception) {
                    e.message ?: "Could not rename"
                }
            }
            if (error != null) showMessage(error) else refresh()
        }
    }

    fun createFolder(name: String) {
        val server = currentServer ?: return
        dismissDialog()
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val clean = name.trim()
                    if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) {
                        throw IllegalArgumentException("This name is not valid")
                    }
                    app.storageOps.mkdir(server, app.storageOps.join(currentPath, clean))
                    app.cacheManager.invalidateFolder(server.id, currentPath)
                    null
                } catch (e: Exception) {
                    e.message ?: "Could not create the folder"
                }
            }
            if (error != null) showMessage(error) else refresh()
        }
    }

    // ---------- server form ----------

    fun toggleEditMode() {
        editMode = !editMode
    }

    fun showAddServer() {
        formServer = null
        showServerForm = true
    }

    fun showEditServer(server: SmbServer) {
        formServer = server
        showServerForm = true
    }

    fun hideServerForm() {
        showServerForm = false
        formServer = null
        editMode = false
        focusRecentFirst = false
        focusPending = true // restore focus on the home screen when the form closes
    }
}
