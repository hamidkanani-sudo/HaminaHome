package com.hamina.home.player

import android.os.Bundle
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.hamina.home.HaminaApp
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.thumb.ImageDecoding
import com.hamina.home.ui.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/** The images of the folder that was open when a photo was selected, so Next/Previous work. */
object PhotoSession {
    @Volatile
    var serverId: String = ""

    @Volatile
    var items: List<MediaItem> = emptyList()
}

/**
 * Full-screen SMB photo viewer.
 *
 * Remote control:
 * - Left / Right: previous / next photo (pan when zoomed)
 * - OK: zoom 1x -> 2x -> 4x -> fit
 * - Up / Down: pan when zoomed; otherwise Up pins the info bar and Down rotates
 * - Play/Pause: slideshow on / off
 * - Menu / Info: help
 * - Back: reset zoom, then close
 */
class PhotoViewerActivity : ComponentActivity() {

    private class Decoded(
        val bitmap: android.graphics.Bitmap,
        val originalWidth: Int,
        val originalHeight: Int
    )

    private var images: List<MediaItem> = emptyList()
    private var server: SmbServer? = null

    private var index by mutableIntStateOf(0)
    private var current by mutableStateOf<Decoded?>(null)
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)

    private var zoomStep by mutableIntStateOf(0)
    private var panX by mutableStateOf(0f)
    private var panY by mutableStateOf(0f)
    private var quarterTurns by mutableIntStateOf(0)
    private var infoPinned by mutableStateOf(false)
    private var infoFlash by mutableStateOf(true)
    private var helpVisible by mutableStateOf(false)
    private var slideshow by mutableStateOf(false)

    private var viewWidth = 0
    private var viewHeight = 0

    private val decodedCache = LruCache<Int, Decoded>(3)
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var slideshowJob: Job? = null
    private var infoJob: Job? = null

    private val app get() = HaminaApp.instance
    private val zoomLevels = floatArrayOf(1f, 2f, 4f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        setContent { ViewerContent() }

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, -1)

        images = if (PhotoSession.serverId == serverId && PhotoSession.items.isNotEmpty()) {
            PhotoSession.items
        } else if (path.isNotBlank()) {
            // The folder list is gone (process was recreated): show only this photo.
            listOf(MediaItem(id = path, name = name, path = path, isDirectory = false, serverId = serverId))
        } else {
            emptyList()
        }

        if (serverId.isBlank() || images.isEmpty()) {
            loading = false
            error = "Image information is missing"
            return
        }

        lifecycleScope.launch {
            server = app.serverRepository.getServersOnce().firstOrNull { it.id == serverId }
            if (server == null) {
                loading = false
                error = "SMB server was not found"
                return@launch
            }
            val first = if (startIndex in images.indices) {
                startIndex
            } else {
                images.indexOfFirst { it.path == path }.coerceAtLeast(0)
            }
            show(first)
        }
    }

    // ---------- loading ----------

    private fun wrap(i: Int): Int {
        val n = images.size
        return ((i % n) + n) % n
    }

    private fun show(requested: Int) {
        if (images.isEmpty()) return
        val target = wrap(requested)
        index = target
        resetView()
        flashInfo()
        loadJob?.cancel()
        prefetchJob?.cancel()
        restartSlideshow()

        val cached = decodedCache.get(target)
        if (cached != null) {
            current = cached
            loading = false
            error = null
            prefetchNeighbors(target)
            return
        }

        current = null
        loading = true
        error = null
        loadJob = lifecycleScope.launch {
            try {
                val decoded = decodeIndex(target)
                decodedCache.put(target, decoded)
                if (index == target) {
                    current = decoded
                    loading = false
                    prefetchNeighbors(target)
                }
            } catch (e: Exception) {
                if (index == target) {
                    loading = false
                    error = e.message ?: "Could not open this image"
                }
            }
        }
    }

    private fun prefetchNeighbors(center: Int) {
        if (images.size < 2) return
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch {
            for (candidate in listOf(wrap(center + 1), wrap(center - 1))) {
                if (!isActive) return@launch
                if (decodedCache.get(candidate) != null) continue
                try {
                    decodedCache.put(candidate, decodeIndex(candidate))
                } catch (_: Exception) {
                    // Not fatal: it will be loaded (and the error shown) when the user gets there.
                }
            }
        }
    }

    private suspend fun decodeIndex(i: Int): Decoded {
        val srv = server ?: throw IllegalStateException("SMB server was not found")
        val item = images[i]
        return withContext(Dispatchers.IO) {
            val handle = app.smbManager.openFileHandle(srv, item.path).getOrThrow()
            try {
                val bytes = handle.readFully(MAX_FILE_BYTES)
                    ?: throw IOException("This image is too large to open")
                val bounds = ImageDecoding.bounds(bytes)
                val bitmap = ImageDecoding.decode(bytes, MAX_SIDE)
                    ?: throw IOException("This image format is not supported on this device")
                Decoded(
                    bitmap = bitmap,
                    originalWidth = bounds?.first ?: bitmap.width,
                    originalHeight = bounds?.second ?: bitmap.height
                )
            } finally {
                handle.close()
            }
        }
    }

    // ---------- view state ----------

    private fun resetView() {
        zoomStep = 0
        panX = 0f
        panY = 0f
        quarterTurns = 0
    }

    private fun flashInfo() {
        infoFlash = true
        infoJob?.cancel()
        infoJob = lifecycleScope.launch {
            delay(3500)
            infoFlash = false
        }
    }

    private fun restartSlideshow() {
        slideshowJob?.cancel()
        if (!slideshow) return
        slideshowJob = lifecycleScope.launch {
            delay(SLIDESHOW_MS)
            if (slideshow) show(index + 1)
        }
    }

    private fun toggleSlideshow() {
        slideshow = !slideshow
        if (slideshow) restartSlideshow() else slideshowJob?.cancel()
        flashInfo()
    }

    private fun cycleZoom() {
        zoomStep = (zoomStep + 1) % zoomLevels.size
        if (zoomStep == 0) {
            panX = 0f
            panY = 0f
        } else {
            clampPan()
        }
        flashInfo()
    }

    private fun rotate() {
        quarterTurns += 1
        panX = 0f
        panY = 0f
        clampPan()
        flashInfo()
    }

    /** Fit factor of the image inside the view for the current rotation (before zoom). */
    private fun fitScale(): Float {
        val bmp = current?.bitmap ?: return 1f
        if (viewWidth <= 0 || viewHeight <= 0) return 1f
        val odd = quarterTurns % 2 != 0
        val w = (if (odd) bmp.height else bmp.width).toFloat()
        val h = (if (odd) bmp.width else bmp.height).toFloat()
        return min(viewWidth / w, viewHeight / h)
    }

    private fun clampPan() {
        val bmp = current?.bitmap ?: return
        val odd = quarterTurns % 2 != 0
        val w = (if (odd) bmp.height else bmp.width) * fitScale() * zoomLevels[zoomStep]
        val h = (if (odd) bmp.width else bmp.height) * fitScale() * zoomLevels[zoomStep]
        val maxX = max(0f, (w - viewWidth) / 2f)
        val maxY = max(0f, (h - viewHeight) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun pan(dx: Float, dy: Float) {
        panX += dx * viewWidth * 0.25f
        panY += dy * viewHeight * 0.25f
        clampPan()
    }

    // ---------- keys ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val zoomed = zoomStep > 0
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when {
                    helpVisible -> helpVisible = false
                    zoomStep > 0 -> {
                        zoomStep = 0
                        panX = 0f
                        panY = 0f
                    }
                    else -> finish()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (zoomed) pan(1f, 0f) else show(index - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (zoomed) pan(-1f, 0f) else show(index + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (zoomed) {
                    pan(0f, 1f)
                } else if (event?.repeatCount == 0) {
                    infoPinned = !infoPinned
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (zoomed) {
                    pan(0f, -1f)
                } else if (event?.repeatCount == 0) {
                    rotate()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (event?.repeatCount == 0) cycleZoom()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                show(index + 1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP -> {
                show(index - 1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event?.repeatCount == 0) toggleSlideshow()
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                if (event?.repeatCount == 0) helpVisible = !helpVisible
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- UI ----------

    @androidx.compose.runtime.Composable
    private fun ViewerContent() {
        val decoded = current
        val extra = run {
            // When rotated by 90 degrees the image needs its own fit factor.
            val bmp = decoded?.bitmap
            if (bmp == null || viewWidth <= 0 || viewHeight <= 0 || quarterTurns % 2 == 0) {
                1f
            } else {
                val rotated = min(viewWidth / bmp.height.toFloat(), viewHeight / bmp.width.toFloat())
                val plain = min(viewWidth / bmp.width.toFloat(), viewHeight / bmp.height.toFloat())
                rotated / plain
            }
        }
        val scale by animateFloatAsState(zoomLevels[zoomStep] * extra, label = "zoom")
        val tx by animateFloatAsState(panX, label = "panX")
        val ty by animateFloatAsState(panY, label = "panY")
        val rotation by animateFloatAsState(quarterTurns * 90f, label = "rotation")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged {
                    viewWidth = it.width
                    viewHeight = it.height
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> CircularProgressIndicator(color = Color(0xFF4C9AFF))
                decoded != null -> {
                    val image = androidx.compose.runtime.remember(decoded) { decoded.bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = tx
                                translationY = ty
                                rotationZ = rotation
                            }
                    )
                }
                else -> Text(
                    text = error ?: "Unable to open image",
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(48.dp)
                )
            }

            if ((infoPinned || infoFlash || slideshow) && images.isNotEmpty()) {
                InfoOverlay(decoded, Modifier.align(Alignment.BottomCenter))
            }
            if (helpVisible) {
                HelpOverlay(Modifier.align(Alignment.Center))
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun InfoOverlay(decoded: Decoded?, modifier: Modifier) {
        val item = images.getOrNull(index) ?: return
        val details = mutableListOf<String>()
        if (decoded != null) details.add("${decoded.originalWidth} × ${decoded.originalHeight}")
        if (item.size > 0) details.add(formatSize(item.size))
        if (item.lastModified > 0) {
            details.add(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.lastModified)))
        }
        if (zoomStep > 0) details.add("Zoom ${zoomLevels[zoomStep].toInt()}×")
        if (slideshow) details.add("Slideshow")

        Column(
            modifier = modifier
                .padding(bottom = 32.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${index + 1} / ${images.size}   ${item.name}",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString("   •   "),
                    color = Color(0xFFB8C2D0),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun HelpOverlay(modifier: Modifier) {
        val lines = listOf(
            "◀ ▶   Previous / next photo",
            "OK   Zoom 2× → 4× → fit",
            "▲ ▼   Pan when zoomed  •  ▲ info  •  ▼ rotate",
            "Play/Pause   Slideshow on / off",
            "Back   Reset zoom, then close"
        )
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE6101620))
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Photo controls", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            lines.forEach { Text(it, color = Color(0xFFD5DCE6), fontSize = 15.sp) }
            Text("Press Menu or Back to close", color = Color(0xFF8D99AB), fontSize = 12.sp)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        decodedCache.evictAll()
    }

    companion object {
        const val EXTRA_SERVER_ID = "photo_server_id"
        const val EXTRA_PATH = "photo_path"
        const val EXTRA_NAME = "photo_name"
        const val EXTRA_INDEX = "photo_index"

        private const val MAX_SIDE = 3072
        private const val MAX_FILE_BYTES = 96L * 1024 * 1024
        private const val SLIDESHOW_MS = 5_000L
    }
}
