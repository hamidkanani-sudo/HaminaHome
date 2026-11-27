package com.hamina.home.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.model.ViewMode
import com.hamina.home.data.playback.PlaybackRepository
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

/** Root of the UI: add/edit form, home screen, or the folder browser. */
@Composable
fun HaminaRoot(
    vm: BrowserViewModel,
    onOpenItem: (MediaItem, Int) -> Unit,
    onPlayRecent: (PlaybackRepository.RecentPlay) -> Unit
) {
    if (vm.showServerForm) {
        AddServerScreen(
            existing = vm.formServer,
            onDismiss = { vm.hideServerForm() },
            onSaved = { vm.hideServerForm() }
        )
        return
    }

    // Back goes up one folder, then to the home screen, then leaves the app.
    BackHandler(enabled = vm.currentServer != null) { vm.goUp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HaminaColors.Background)
    ) {
        if (vm.currentServer == null) {
            HomeScreen(vm, onPlayRecent)
        } else {
            BrowserScreen(vm, onOpenItem)
        }
    }
}

// =====================================================================================
// Home
// =====================================================================================

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = HaminaColors.TextSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 6.dp)
    )
}

@Composable
private fun HomeScreen(
    vm: BrowserViewModel,
    onPlayRecent: (PlaybackRepository.RecentPlay) -> Unit
) {
    val recentRequesters = remember(vm.recent.size) { List(vm.recent.size) { FocusRequester() } }
    val serverRequesters = remember(vm.servers.size) { List(vm.servers.size) { FocusRequester() } }
    val addRequester = remember { FocusRequester() }

    // Initial focus: first "Continue watching" card, otherwise a server (or "Add server").
    LaunchedEffect(vm.focusPending, vm.homeReady, vm.recent.size, vm.servers.size) {
        if (!vm.focusPending || !vm.homeReady) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val target = when {
            vm.focusRecentFirst && recentRequesters.isNotEmpty() -> recentRequesters[0]
            serverRequesters.isNotEmpty() ->
                serverRequesters[vm.focusIndex.coerceIn(0, serverRequesters.size - 1)]
            else -> addRequester
        }
        try {
            target.requestFocus()
        } catch (_: Exception) {
        }
        vm.consumeFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hamina Home",
                    color = HaminaColors.TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Your media on the home network",
                    color = HaminaColors.TextSecondary,
                    fontSize = 14.sp
                )
            }
            ToolbarChip(
                icon = Icons.Rounded.Add,
                label = "Add server",
                onClick = { vm.showAddServer() }
            )
            if (vm.servers.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                ToolbarChip(
                    icon = Icons.Rounded.Edit,
                    label = if (vm.editMode) "Done" else "Edit servers",
                    highlighted = vm.editMode,
                    onClick = { vm.toggleEditMode() }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (vm.recent.isNotEmpty()) {
            SectionTitle("Continue watching")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
            ) {
                itemsIndexed(vm.recent) { index, entry ->
                    RecentCard(
                        entry = entry,
                        requester = recentRequesters[index],
                        onClick = { onPlayRecent(entry) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        SectionTitle(if (vm.editMode) "Servers — select one to edit" else "Servers")
        if (vm.servers.isEmpty()) {
            Row(modifier = Modifier.padding(6.dp, 10.dp)) {
                AddServerCard(requester = addRequester, onClick = { vm.showAddServer() })
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
            ) {
                itemsIndexed(vm.servers) { index, server ->
                    ServerCard(
                        server = server,
                        editMode = vm.editMode,
                        requester = serverRequesters[index],
                        onFocused = { vm.focusIndex = index },
                        onClick = {
                            if (vm.editMode) vm.showEditServer(server) else vm.openServer(server, index)
                        },
                        onMenu = { vm.showEditServer(server) }
                    )
                }
                item {
                    AddServerCard(requester = null, onClick = { vm.showAddServer() })
                }
            }
        }
    }
}

@Composable
private fun RecentCard(
    entry: PlaybackRepository.RecentPlay,
    requester: FocusRequester,
    onClick: () -> Unit
) {
    FocusSurface(
        modifier = Modifier
            .width(280.dp)
            .height(136.dp),
        focusRequester = requester,
        onClick = onClick
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(HaminaColors.Video.copy(alpha = 0.30f), Color.Transparent)
                    )
                )
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = HaminaColors.Video,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(32.dp)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
            ) {
                Text(
                    text = entry.name,
                    color = HaminaColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Resume at ${formatDuration(entry.positionMs)}",
                    color = HaminaColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
            if (entry.durationMs > 0) {
                val fraction = (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x33FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(HaminaColors.Accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: SmbServer,
    editMode: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    FocusSurface(
        modifier = Modifier
            .width(250.dp)
            .height(112.dp),
        focusRequester = requester,
        onFocused = onFocused,
        onMenuKey = onMenu,
        onClick = onClick
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = Icons.Rounded.Dns, tint = HaminaColors.Accent, size = 40.dp)
                Spacer(Modifier.weight(1f))
                if (editMode) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = HaminaColors.Folder,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column {
                Text(
                    text = server.name,
                    color = HaminaColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (server.share.isBlank()) {
                        "${server.host}  •  all shares"
                    } else {
                        "${server.host} / ${server.share}"
                    },
                    color = HaminaColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AddServerCard(requester: FocusRequester?, onClick: () -> Unit) {
    FocusSurface(
        modifier = Modifier
            .width(200.dp)
            .height(112.dp),
        focusRequester = requester,
        onClick = onClick
    ) { _ ->
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = HaminaColors.Success,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(text = "Add server", color = HaminaColors.TextPrimary, fontSize = 15.sp)
        }
    }
}

// =====================================================================================
// Browser
// =====================================================================================

@Composable
private fun BrowserScreen(vm: BrowserViewModel, onOpenItem: (MediaItem, Int) -> Unit) {
    val server = vm.currentServer ?: return
    val segments = vm.currentPath.split('/').filter { it.isNotEmpty() }
    val title = if (segments.isEmpty()) server.name else segments.last()
    val breadcrumb = (listOf(server.name) + segments).joinToString("  ›  ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = HaminaColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = breadcrumb,
                    color = HaminaColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(16.dp))
            ToolbarChip(
                icon = Icons.Rounded.SwapVert,
                label = "Sort: ${sortLabel(vm.sortMode)}",
                onClick = { vm.cycleSort() }
            )
            Spacer(Modifier.width(10.dp))
            ToolbarChip(
                icon = if (vm.viewMode == ViewMode.LIST) Icons.Rounded.GridView else Icons.Rounded.ViewAgenda,
                label = if (vm.viewMode == ViewMode.LIST) "Grid view" else "List view",
                onClick = { vm.toggleViewMode() }
            )
            Spacer(Modifier.width(10.dp))
            ToolbarChip(
                icon = Icons.Rounded.Refresh,
                label = "Refresh",
                onClick = { vm.refresh() }
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val error = vm.errorMessage
            when {
                vm.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = HaminaColors.Accent)
                    }
                }
                error != null -> ErrorPanel(error) { vm.retry() }
                vm.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "This folder is empty",
                            color = HaminaColors.TextSecondary,
                            fontSize = 18.sp
                        )
                    }
                }
                vm.viewMode == ViewMode.GRID -> MediaGrid(vm, onOpenItem)
                else -> MediaList(vm, onOpenItem)
            }
        }

        InfoBar(vm.selectedItem)
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(message) {
        withFrameNanos { }
        try {
            retryFocus.requestFocus()
        } catch (_: Exception) {
        }
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            color = HaminaColors.Danger,
            fontSize = 17.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        ToolbarChip(
            icon = Icons.Rounded.Refresh,
            label = "Retry",
            focusRequester = retryFocus,
            onClick = onRetry
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Press Back to return",
            color = HaminaColors.TextSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun InfoBar(selected: MediaItem?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(top = 8.dp, start = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = selected?.name ?: "",
                color = HaminaColors.TextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (selected != null) itemSubtitle(selected) else "",
                color = HaminaColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        Text(
            text = "Back: up one level",
            color = HaminaColors.TextSecondary,
            fontSize = 12.sp
        )
    }
}

/** Moves focus to the entry the view model asked for (after loading, going back, ...). */
@Composable
private fun FocusRestore(
    vm: BrowserViewModel,
    count: Int,
    requesters: List<FocusRequester>,
    scrollTo: suspend (Int) -> Unit
) {
    LaunchedEffect(vm.focusPending, count) {
        if (!vm.focusPending || count == 0) return@LaunchedEffect
        val index = vm.focusIndex.coerceIn(0, count - 1)
        scrollTo(index)
        withFrameNanos { }
        withFrameNanos { }
        try {
            requesters[index].requestFocus()
        } catch (_: Exception) {
            // Row not attached yet; the user can still move with the D-pad.
        }
        vm.consumeFocus()
    }
}

@Composable
private fun MediaList(vm: BrowserViewModel, onOpenItem: (MediaItem, Int) -> Unit) {
    val items = vm.items
    val listState = rememberLazyListState()
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }

    FocusRestore(vm, items.size, requesters) { index ->
        listState.scrollToItem((index - 2).coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
    ) {
        itemsIndexed(items) { index, item ->
            MediaRow(
                item = item,
                requester = requesters[index],
                onFocused = { vm.focusIndex = index },
                onClick = { onOpenItem(item, index) }
            )
        }
    }
}

@Composable
private fun MediaGrid(vm: BrowserViewModel, onOpenItem: (MediaItem, Int) -> Unit) {
    val items = vm.items
    val gridState = rememberLazyGridState()
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }

    FocusRestore(vm, items.size, requesters) { index ->
        gridState.scrollToItem((index - 4).coerceAtLeast(0))
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(6.dp)
    ) {
        gridItemsIndexed(items) { index, item ->
            MediaCard(
                item = item,
                requester = requesters[index],
                onFocused = { vm.focusIndex = index },
                onClick = { onOpenItem(item, index) }
            )
        }
    }
}

@Composable
private fun MediaRow(
    item: MediaItem,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val visual = visualFor(item)
    FocusSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        focusRequester = requester,
        focusedScale = 1.02f,
        onFocused = onFocused,
        onClick = onClick
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon = visual.icon, tint = visual.color, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    color = HaminaColors.TextPrimary,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = itemSubtitle(item),
                    color = HaminaColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
            if (item.lastPlayedPosition > 0) {
                Spacer(Modifier.width(12.dp))
                ResumeBadge(item.lastPlayedPosition)
            }
        }
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val visual = visualFor(item)
    FocusSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        focusRequester = requester,
        focusedScale = 1.05f,
        onFocused = onFocused,
        onClick = onClick
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                visual.color.copy(alpha = 0.30f),
                                visual.color.copy(alpha = 0.08f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.color,
                    modifier = Modifier.size(40.dp)
                )
                if (item.lastPlayedPosition > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        ResumeBadge(item.lastPlayedPosition)
                    }
                }
            }
            Text(
                text = item.name,
                color = HaminaColors.TextPrimary,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
