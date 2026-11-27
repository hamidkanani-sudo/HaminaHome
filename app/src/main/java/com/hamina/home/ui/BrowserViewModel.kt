package com.hamina.home.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hamina.home.HaminaApp
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.model.SortMode
import com.hamina.home.util.NaturalOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds all browsing state outside the Activity, so it survives configuration changes and so the
 * navigation rules (Back, Retry, focus restore) live in one place.
 */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private class Frame(val path: String, val focusIndex: Int)

    private val app get() = HaminaApp.instance

    var servers by mutableStateOf<List<SmbServer>>(emptyList())
        private set
    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** The server we are inside of (null = server list is shown). */
    var currentServer by mutableStateOf<SmbServer?>(null)
        private set
    var currentPath by mutableStateOf("")
        private set
    var sortMode by mutableStateOf(SortMode.NAME_ASC)
        private set

    /** Index of the focused row in whichever list is shown. Updated by the UI on focus changes. */
    var focusIndex by mutableIntStateOf(0)

    /** Bumped whenever the UI should move focus to [focusIndex] (after loading, going back, ...). */
    var focusToken by mutableIntStateOf(0)
        private set

    var showServerForm by mutableStateOf(false)
        private set
    var formServer by mutableStateOf<SmbServer?>(null)
        private set

    val selectedItem: MediaItem?
        get() = if (currentServer != null) items.getOrNull(focusIndex) else null

    private val stack = ArrayDeque<Frame>()
    private var hasListing = false
    private var lastServerIndex = 0
    private var loadJob: Job? = null
    private var retryAction: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            app.serverRepository.servers.collect { servers = it }
        }
        viewModelScope.launch {
            app.settingsRepository.sortMode.collect { mode ->
                if (mode != sortMode) {
                    sortMode = mode
                    items = sortItems(items, mode)
                }
            }
        }
    }

    // ---------- navigation ----------

    fun openServer(server: SmbServer, index: Int) {
        lastServerIndex = index
        stack.clear()
        items = emptyList()
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
            if (!hasListing) leaveServer() else focusToken++
            return true
        }
        if (errorMessage != null) {
            errorMessage = null
            retryAction = null
            if (!hasListing) leaveServer() else focusToken++
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
        hasListing = false
        isLoading = false
        errorMessage = null
        retryAction = null
        stack.clear()
        focusIndex = lastServerIndex
        focusToken++
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

        loadJob = viewModelScope.launch {
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
                focusIndex = restoreIndex.coerceIn(0, (ordered.size - 1).coerceAtLeast(0))
                focusToken++
            } else {
                errorMessage = result.exceptionOrNull()?.message ?: "Network error. Check connection."
            }
        }
    }

    // ---------- playback info ----------

    /** Called when returning from the player so "resume" info and "last played" order are current. */
    fun refreshPlayback() {
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

    // ---------- sorting ----------

    fun cycleSort() {
        val modes = SortMode.values()
        val next = modes[(modes.indexOf(sortMode) + 1) % modes.size]
        sortMode = next
        items = sortItems(items, next)
        viewModelScope.launch { app.settingsRepository.setSortMode(next) }
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

    // ---------- server form ----------

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
        focusToken++ // restore focus on the list when the form closes
    }
}
