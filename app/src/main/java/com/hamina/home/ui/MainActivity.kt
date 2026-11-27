package com.hamina.home.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamina.home.data.model.MediaItem
import com.hamina.home.player.PhotoViewerActivity
import com.hamina.home.player.PlayerActivity

/**
 * Main browser for Hamina Home. All navigation state lives in [BrowserViewModel].
 */
class MainActivity : ComponentActivity() {

    private val vm: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HaminaHomeScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPlayback()
    }

    private fun openItem(item: MediaItem, index: Int) {
        val server = vm.currentServer ?: return
        when {
            item.isDirectory -> vm.openFolder(item, index)
            item.isVideo -> {
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_SERVER_ID, server.id)
                    putExtra(PlayerActivity.EXTRA_PATH, item.path)
                    putExtra(PlayerActivity.EXTRA_NAME, item.name)
                    putExtra(PlayerActivity.EXTRA_MEDIA_ID, item.id)
                }
                startActivity(intent)
            }
            item.isImage -> {
                val intent = Intent(this, PhotoViewerActivity::class.java).apply {
                    putExtra(PhotoViewerActivity.EXTRA_SERVER_ID, server.id)
                    putExtra(PhotoViewerActivity.EXTRA_PATH, item.path)
                    putExtra(PhotoViewerActivity.EXTRA_NAME, item.name)
                }
                startActivity(intent)
            }
            else -> Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun HaminaHomeScreen() {
        // Full-screen add/edit form (TV-friendly, all fields focusable)
        if (vm.showServerForm) {
            AddServerScreen(
                existing = vm.formServer,
                onDismiss = { vm.hideServerForm() },
                onSaved = { vm.hideServerForm() }
            )
            return
        }

        // Back goes up one folder, then to the server list, then leaves the app.
        BackHandler(enabled = vm.currentServer != null) { vm.goUp() }

        val server = vm.currentServer
        val selected = vm.selectedItem
        val addServerFocus = remember { FocusRequester() }

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
                Text(text = "Hamina Home", color = Color(0xFF42A5F5), fontSize = 24.sp)
                Spacer(Modifier.height(20.dp))

                if (selected != null) {
                    Text(
                        text = selected.name,
                        color = Color.White,
                        fontSize = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when {
                            selected.isDirectory -> "Folder"
                            selected.isVideo -> "Video • ${formatSize(selected.size)}"
                            selected.isImage -> "Image • ${formatSize(selected.size)}"
                            else -> formatSize(selected.size)
                        },
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp
                    )
                    if (selected.lastPlayedPosition > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "▶ Resume available",
                            color = Color(0xFF81C784),
                            fontSize = 13.sp
                        )
                    }
                } else if (server == null) {
                    Text(
                        text = "Select a server\nor add a new one",
                        color = Color.Gray,
                        fontSize = 15.sp
                    )
                } else {
                    Text(text = "Select a file", color = Color.Gray, fontSize = 15.sp)
                }

                Spacer(Modifier.weight(1f))
                if (server != null) {
                    Text(
                        text = "Refresh folder  (OK)",
                        color = Color(0xFF90CAF9),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { vm.refresh() }
                            .padding(vertical = 6.dp)
                    )
                }
                Text(
                    text = "Sort: ${vm.sortMode.name.replace('_', ' ').lowercase()}  (OK to change)",
                    color = Color(0xFF90CAF9),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { vm.cycleSort() }
                        .padding(vertical = 6.dp)
                )
            }

            // ===== MAIN AREA =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val title = when {
                        server == null -> "Servers"
                        vm.currentPath.isEmpty() -> server.name
                        else -> vm.currentPath.substringAfterLast('/')
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (server == null) {
                        Text(
                            text = "+ Add Server",
                            color = Color(0xFF66BB6A),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .focusRequester(addServerFocus)
                                .clickable { vm.showAddServer() }
                                .padding(8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                val error = vm.errorMessage
                when {
                    vm.isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF42A5F5))
                        }
                    }
                    error != null -> {
                        val retryFocus = remember { FocusRequester() }
                        LaunchedEffect(error) {
                            withFrameNanos { }
                            try {
                                retryFocus.requestFocus()
                            } catch (_: Exception) {
                            }
                        }
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(error, color = Color(0xFFEF5350), fontSize = 16.sp)
                            Spacer(Modifier.height(20.dp))
                            Text(
                                text = "Retry",
                                color = Color(0xFF42A5F5),
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .focusRequester(retryFocus)
                                    .clickable { vm.retry() }
                                    .padding(12.dp)
                            )
                            Text(
                                text = "Press Back to return",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    }
                    server == null -> {
                        if (vm.servers.isEmpty()) {
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                try {
                                    addServerFocus.requestFocus()
                                } catch (_: Exception) {
                                }
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "No servers configured.\n\nSelect + Add Server above.",
                                    color = Color.Gray,
                                    fontSize = 16.sp
                                )
                            }
                        } else {
                            TvList(vm.servers) { index, s, requester ->
                                FocusableRow(
                                    text = "▶  ${s.name}",
                                    subText = if (s.share.isBlank()) {
                                        "${s.host}  (all shares)"
                                    } else {
                                        "${s.host} / ${s.share}"
                                    },
                                    isSelected = vm.focusIndex == index,
                                    focusRequester = requester,
                                    onFocus = { vm.focusIndex = index },
                                    onClick = { vm.openServer(s, index) },
                                    onLongClick = { vm.showEditServer(s) }
                                )
                            }
                        }
                    }
                    else -> {
                        if (vm.items.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("This folder is empty", color = Color.Gray, fontSize = 16.sp)
                            }
                        } else {
                            TvList(vm.items) { index, item, requester ->
                                FocusableRow(
                                    text = "${iconFor(item)}  ${item.name}",
                                    subText = if (item.isDirectory) "Folder" else formatSize(item.size),
                                    isSelected = vm.focusIndex == index,
                                    focusRequester = requester,
                                    onFocus = { vm.focusIndex = index },
                                    onClick = { openItem(item, index) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Lazy list that puts focus back on [BrowserViewModel.focusIndex] whenever the view model asks
     * (after loading a folder, going back, closing the form, ...).
     */
    @Composable
    private fun <T> TvList(
        entries: List<T>,
        row: @Composable (index: Int, entry: T, focusRequester: FocusRequester) -> Unit
    ) {
        val listState = rememberLazyListState()
        val requesters = remember(entries.size) { List(entries.size) { FocusRequester() } }

        LaunchedEffect(vm.focusToken, entries.size) {
            if (entries.isEmpty()) return@LaunchedEffect
            val index = vm.focusIndex.coerceIn(0, entries.lastIndex)
            listState.scrollToItem((index - 2).coerceAtLeast(0))
            withFrameNanos { }
            try {
                requesters[index].requestFocus()
            } catch (_: Exception) {
                // Row not attached yet; the user can still navigate with the D-pad.
            }
        }

        LazyColumn(state = listState) {
            itemsIndexed(entries) { index, entry ->
                row(index, entry, requesters[index])
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun FocusableRow(
        text: String,
        subText: String,
        isSelected: Boolean,
        focusRequester: FocusRequester,
        onFocus: () -> Unit,
        onClick: () -> Unit,
        onLongClick: () -> Unit = {}
    ) {
        val bg = if (isSelected) Color(0xFF1565C0) else Color.Transparent
        val borderColor = if (isSelected) Color(0xFF42A5F5) else Color.Transparent
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusable()
                .onFocusChanged { if (it.isFocused) onFocus() }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) {
                        when (event.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                            AndroidKeyEvent.KEYCODE_ENTER -> {
                                onClick(); true
                            }
                            AndroidKeyEvent.KEYCODE_MENU,
                            AndroidKeyEvent.KEYCODE_INFO -> {
                                onLongClick(); true
                            }
                            else -> false
                        }
                    } else false
                }
                .focusRequester(focusRequester)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(bg)
                .border(2.dp, borderColor)
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
                Text(text = subText, color = Color(0xFFAAAAAA), fontSize = 13.sp)
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

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        if (bytes < 1024 * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
        return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
