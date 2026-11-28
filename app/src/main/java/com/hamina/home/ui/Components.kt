package com.hamina.home.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.hamina.home.HaminaApp
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.model.SortMode
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

object HaminaColors {
    val Background = Color(0xFF0A0E14)
    val Surface = Color(0xFF141A22)
    val SurfaceFocused = Color(0xFF212C3B)
    val Accent = Color(0xFF4C9AFF)
    val TextPrimary = Color(0xFFF2F5F9)
    val TextSecondary = Color(0xFF9AA7B8)
    val Success = Color(0xFF4CD08B)
    val Danger = Color(0xFFFF6B6B)
    val Folder = Color(0xFFFFC857)
    val Video = Color(0xFF7C9CFF)
    val Image = Color(0xFF4CD08B)
    val Audio = Color(0xFFFF8FB1)
    val Other = Color(0xFF9AA7B8)
}

/**
 * Ignores clicks that arrive right after another accepted click. Remote controls can deliver a
 * key-up twice (or the next screen can receive the tail of the previous press), which used to
 * trigger several actions at once.
 */
object ClickGuard {
    private var last = 0L

    fun allow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - last < 350L) return false
        last = now
        return true
    }
}

/**
 * The one focusable building block used for every card, row and button. It has a single focus
 * target, so the highlight always follows the real focus: the card grows, gets a brighter
 * background and an accent border.
 */
@Composable
fun FocusSurface(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.04f,
    onFocused: () -> Unit = {},
    onMenuKey: (() -> Unit)? = null,
    onLeftKey: (() -> Unit)? = null,
    onRightKey: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        label = "focusScale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) HaminaColors.Accent else Color.Transparent,
        label = "focusBorder"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (focused) HaminaColors.SurfaceFocused else HaminaColors.Surface,
        label = "focusBackground"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val longPressFired = remember { BooleanArray(1) }

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(backgroundColor, shape)
            .border(2.dp, borderColor, shape)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused || state.hasFocus
                if (nowFocused != focused) focused = nowFocused
                if (nowFocused) onFocused()
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val isOk = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    code == AndroidKeyEvent.KEYCODE_ENTER
                if (isOk && onLongClick != null) {
                    // Holding OK opens the actions; a short press clicks (on key up).
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.nativeKeyEvent.repeatCount >= 1 && !longPressFired[0]) {
                            longPressFired[0] = true
                            onLongClick()
                        }
                    } else if (event.type == KeyEventType.KeyUp) {
                        if (longPressFired[0]) {
                            longPressFired[0] = false
                        } else if (ClickGuard.allow()) {
                            onClick()
                        }
                    }
                    true
                } else if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT && onLeftKey != null) {
                    if (event.type == KeyEventType.KeyDown) onLeftKey()
                    true
                } else if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && onRightKey != null) {
                    if (event.type == KeyEventType.KeyDown) onRightKey()
                    true
                } else if (event.type == KeyEventType.KeyUp) {
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER -> {
                            if (ClickGuard.allow()) onClick()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_MENU,
                        AndroidKeyEvent.KEYCODE_INFO -> {
                            if (onMenuKey != null) {
                                onMenuKey()
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (ClickGuard.allow()) onClick() }
            )
    ) {
        content(focused)
    }
}

/** A pill-shaped button for the top bar (sort, view, refresh, add server, ...). */
@Composable
fun ToolbarChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    highlighted: Boolean = false
) {
    FocusSurface(
        modifier = modifier,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(50),
        focusedScale = 1.06f,
        onClick = onClick
    ) { focused ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (focused || highlighted) HaminaColors.Accent else HaminaColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = HaminaColors.TextPrimary,
                fontSize = 15.sp,
                maxLines = 1
            )
        }
    }
}

/** Rounded square with a tinted background and an icon, used for file types. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.6f)
        )
    }
}

@Composable
fun ResumeBadge(positionMs: Long) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(HaminaColors.Success.copy(alpha = 0.22f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = HaminaColors.Success,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = formatDuration(positionMs),
            color = HaminaColors.Success,
            fontSize = 12.sp
        )
    }
}

// ---------- visuals and formatting ----------

class Visual(val icon: ImageVector, val color: Color)

fun visualFor(item: MediaItem): Visual = when {
    item.isDirectory -> Visual(Icons.Rounded.Folder, HaminaColors.Folder)
    item.isVideo -> Visual(Icons.Rounded.Movie, HaminaColors.Video)
    item.isImage -> Visual(Icons.Rounded.Image, HaminaColors.Image)
    item.isAudio -> Visual(Icons.Rounded.MusicNote, HaminaColors.Audio)
    else -> Visual(Icons.Rounded.Description, HaminaColors.Other)
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
    return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun itemSubtitle(item: MediaItem): String {
    if (item.isDirectory) return "Folder"
    val parts = mutableListOf(formatSize(item.size))
    if (item.lastModified > 0) {
        parts.add(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.lastModified)))
    }
    return parts.joinToString("  •  ")
}

fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME_ASC -> "Name A–Z"
    SortMode.NAME_DESC -> "Name Z–A"
    SortMode.DATE_ASC -> "Oldest first"
    SortMode.DATE_DESC -> "Newest first"
    SortMode.SIZE_ASC -> "Smallest first"
    SortMode.SIZE_DESC -> "Largest first"
    SortMode.LAST_PLAYED -> "Recently played"
}


// ---------- thumbnails ----------

/**
 * Loads the thumbnail of [item] in the background. The cached version appears immediately;
 * a missing one is only created when [generate] is true (images always, videos when focused),
 * so browsing never waits for it. Leaving the screen or losing focus cancels a pending request.
 */
@Composable
fun rememberThumbnail(server: SmbServer?, item: MediaItem, generate: Boolean): State<Bitmap?> {
    val manager = HaminaApp.instance.thumbnailManager
    val itemKey = "${item.id}_${item.size}_${item.lastModified}"
    return produceState<Bitmap?>(manager.peekMemory(item), itemKey, generate) {
        if (!manager.supports(item)) return@produceState
        if (value != null) return@produceState
        val cached = manager.peek(item)
        if (cached != null) {
            value = cached
            return@produceState
        }
        if (generate && server != null) {
            // Wait until focus settles, so scrolling over videos does not start work for each one.
            if (item.isVideo) delay(300)
            value = manager.load(server, item)
        }
    }
}

@Composable
fun ThumbImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}
