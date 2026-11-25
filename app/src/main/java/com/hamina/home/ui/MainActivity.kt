package com.hamina.home.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.hamina.home.HaminaApp
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.model.SortMode
import com.hamina.home.data.model.ViewMode
import com.hamina.home.player.PlayerActivity
import kotlinx.coroutines.launch

/**
 * Main browser for Hamina Home.
 * Supports:
 * - Multiple SMB servers
 * - Folder navigation with cache
 * - List view (grid foundation ready)
 * - Left details panel
 * - Predictable Back button
 * - Launch built-in player
 * - Basic add-server flow (via simple dialog later)
 */
class MainActivity : ComponentActivity() {

    private val app get() = HaminaApp.instance

    // Navigation state held outside Compose so Back key can access it
    private var navServer: SmbServer? = null
    private var navPath: String = ""
    private var pathStack = mutableListOf<String>() // for proper up navigation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HaminaHomeScreen()
        }
    }

    @Composable
    private fun HaminaHomeScreen() {
        var servers by remember { mutableStateOf<List<SmbServer>>(emptyList()) }
        var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        var selectedIndex by remember { mutableStateOf(0) }
        var selectedItem by remember { mutableStateOf<MediaItem?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var viewMode by remember { mutableStateOf(ViewMode.LIST) }
        var sortMode by remember { mutableStateOf(SortMode.NAME_ASC) }

        // Load servers & settings
        LaunchedEffect(Unit) {
            app.serverRepository.servers.collect { servers = it }
        }
        LaunchedEffect(Unit) {
            app.settingsRepository.viewMode.collect { viewMode = it }
        }
        LaunchedEffect(Unit) {
            app.settingsRepository.sortMode.collect { sortMode = it }
        }

        fun loadFolder(server: SmbServer, path: String, force: Boolean = false) {
            isLoading = true
            errorMessage = null
            lifecycleScope.launch {
                val result = app.smbManager.listDirectory(server, path, force)
                isLoading = false
                result.onSuccess { list ->
                    items = sortItems(list, sortMode)
                    navServer = server
                    navPath = path
                    selectedIndex = 0
                    selectedItem = items.firstOrNull()
                }.onFailure {
                    errorMessage = it.message ?: "Network error. Check connection."
                    items = emptyList()
                }
            }
        }

        fun openItem(item: MediaItem) {
            val server = navServer ?: return
            if (item.isDirectory) {
                pathStack.add(navPath)
                loadFolder(server, item.path)
            } else if (item.isVideo) {
                val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_SERVER_ID, server.id)
                    putExtra(PlayerActivity.EXTRA_PATH, item.path)
                    putExtra(PlayerActivity.EXTRA_NAME, item.name)
                    putExtra(PlayerActivity.EXTRA_MEDIA_ID, item.id)
                }
                startActivity(intent)
            } else if (item.isImage) {
                val intent = Intent(this@MainActivity, com.hamina.home.player.PhotoViewerActivity::class.java).apply {
                    putExtra(com.hamina.home.player.PhotoViewerActivity.EXTRA_SERVER_ID, server.id)
                    putExtra(com.hamina.home.player.PhotoViewerActivity.EXTRA_PATH, item.path)
                    putExtra(com.hamina.home.player.PhotoViewerActivity.EXTRA_NAME, item.name)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@MainActivity, "Unsupported file type", Toast.LENGTH_SHORT).show()
            }
        }

        fun goUp() {
            if (pathStack.isNotEmpty()) {
                val parent = pathStack.removeAt(pathStack.lastIndex)
                navServer?.let { loadFolder(it, parent) }
            } else if (navServer != null) {
                // Back to server list
                navServer = null
                navPath = ""
                items = emptyList()
                selectedItem = null
            } else {
                // Already at root – exit or minimize
                finish()
            }
        }

        // Android TV Back navigates to the parent folder before leaving the server.
        BackHandler(enabled = navServer != null) { goUp() }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D))
        ) {
            // ===== LEFT DETAILS PANEL =====
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF161616))
                    .padding(20.dp)
            ) {
                Text(
                    text = "Hamina Home",
                    color = Color(0xFF42A5F5),
                    fontSize = 24.sp
                )
                Spacer(Modifier.height(20.dp))

                if (selectedItem != null) {
                    Text(
                        text = selectedItem!!.name,
                        color = Color.White,
                        fontSize = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when {
                            selectedItem!!.isDirectory -> "Folder"
                            selectedItem!!.isVideo -> "Video • ${formatSize(selectedItem!!.size)}"
                            selectedItem!!.isImage -> "Image • ${formatSize(selectedItem!!.size)}"
                            else -> formatSize(selectedItem!!.size)
                        },
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp
                    )
                    if (selectedItem!!.lastPlayedPosition > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "▶ Resume available",
                            color = Color(0xFF81C784),
                            fontSize = 13.sp
                        )
                    }
                } else if (navServer == null) {
                    Text(
                        text = "Select a server\nor add a new one",
                        color = Color.Gray,
                        fontSize = 15.sp
                    )
                } else {
                    Text(
                        text = "Select a file",
                        color = Color.Gray,
                        fontSize = 15.sp
                    )
                }

                Spacer(Modifier.weight(1f))
                Text(
                    text = if (viewMode == ViewMode.LIST) "View: List" else "View: Grid",
                    color = Color(0xFF90CAF9),
                    fontSize = 13.sp
                )
                Text(
                    text = "Sort: ${sortMode.name.replace('_', ' ').lowercase()}",
                    color = Color(0xFF90CAF9),
                    fontSize = 13.sp
                )
            }

            // ===== MAIN AREA =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val title = when {
                        navServer == null -> "Servers"
                        navPath.isEmpty() -> navServer!!.name
                        else -> navPath.substringAfterLast('/')
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (navServer == null) {
                        Text(
                            text = "+ Add Server",
                            color = Color(0xFF66BB6A),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .clickable { AddServerHelper.showAddDialog(this@MainActivity) { } }
                                .padding(8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                when {
                    isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF42A5F5))
                        }
                    }
                    errorMessage != null -> {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(errorMessage!!, color = Color(0xFFEF5350), fontSize = 16.sp)
                            Spacer(Modifier.height(20.dp))
                            Text(
                                text = "Retry",
                                color = Color(0xFF42A5F5),
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .clickable { navServer?.let { loadFolder(it, navPath, force = true) } }
                                    .padding(12.dp)
                            )
                        }
                    }
                    navServer == null -> {
                        if (servers.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "No servers configured.\n\nPress + Add Server\nor long-press to add one.",
                                    color = Color.Gray,
                                    fontSize = 16.sp
                                )
                            }
                        } else {
                            val listState = rememberLazyListState()
                            LazyColumn(state = listState) {
                                itemsIndexed(servers) { index, server ->
                                    FocusableRow(
                                        text = "▶  ${server.name}",
                                        subText = "${server.host} / ${server.share}",
                                        isSelected = selectedIndex == index,
                                        onFocus = {
                                            selectedIndex = index
                                            selectedItem = null
                                        },
                                        onClick = {
                                            pathStack.clear()
                                            loadFolder(server, "")
                                        },
                                        onLongClick = {
                                            AddServerHelper.showServerOptions(this@MainActivity, server) { }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        if (items.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("This folder is empty", color = Color.Gray, fontSize = 16.sp)
                            }
                        } else {
                            val listState = rememberLazyListState()
                            LazyColumn(state = listState) {
                                itemsIndexed(items) { index, item ->
                                    FocusableRow(
                                        text = "${iconFor(item)}  ${item.name}",
                                        subText = if (item.isDirectory) "Folder" else formatSize(item.size),
                                        isSelected = selectedIndex == index,
                                        onFocus = {
                                            selectedIndex = index
                                            selectedItem = item
                                        },
                                        onClick = { openItem(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FocusableRow(
        text: String,
        subText: String,
        isSelected: Boolean,
        onFocus: () -> Unit,
        onClick: () -> Unit,
        onLongClick: () -> Unit = {}
    ) {
        val bg = if (isSelected) Color(0xFF1565C0) else Color.Transparent
        val borderColor = if (isSelected) Color(0xFF42A5F5) else Color.Transparent
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .border(2.dp, borderColor)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subText.isNotBlank()) {
                Text(
                    text = subText,
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp
                )
            }
        }
    }

    private fun iconFor(item: MediaItem): String = when {
        item.isDirectory -> "📁"
        item.isVideo -> "🎬"
        item.isImage -> "🖼"
        item.isAudio -> "🎵"
        else -> "📄"
    }

    private fun sortItems(list: List<MediaItem>, mode: SortMode): List<MediaItem> {
        val dirsFirst = compareBy<MediaItem> { !it.isDirectory }
        return when (mode) {
            SortMode.NAME_ASC -> list.sortedWith(dirsFirst.thenBy { it.name.lowercase() })
            SortMode.NAME_DESC -> list.sortedWith(dirsFirst.thenByDescending { it.name.lowercase() })
            SortMode.DATE_ASC -> list.sortedWith(dirsFirst.thenBy { it.lastModified })
            SortMode.DATE_DESC -> list.sortedWith(dirsFirst.thenByDescending { it.lastModified })
            SortMode.SIZE_ASC -> list.sortedWith(dirsFirst.thenBy { it.size })
            SortMode.SIZE_DESC -> list.sortedWith(dirsFirst.thenByDescending { it.size })
            SortMode.LAST_PLAYED -> list.sortedWith(
                dirsFirst.thenByDescending { it.lastPlayedAt }
            )
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        if (bytes < 1024 * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
        return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

}
