package com.hamina.home.data.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.smb.SmbFileHandle
import com.hamina.home.data.smb.SmbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Creates and caches thumbnails for images and video frames.
 *
 * Folder listings never wait for this class. The UI asks for thumbnails lazily; requests wait in
 * a small queue (so only a couple of files are read from the server at a time) and are dropped
 * automatically when the item scrolls out of sight. Results are kept in memory and on disk.
 */
class ThumbnailManager(
    context: Context,
    private val smbManager: SmbManager
) {

    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val imageGate = Semaphore(2)
    private val videoGate = Semaphore(1)

    // Files that cannot produce a thumbnail (unsupported format, too large, ...).
    private val noThumbnail = ConcurrentHashMap.newKeySet<String>()

    fun supports(item: MediaItem): Boolean = !item.isDirectory && (item.isImage || item.isVideo)

    private fun key(item: MediaItem): String = "${item.id}_${item.size}_${item.lastModified}"

    private fun fileFor(item: MediaItem): File = File(dir, key(item) + ".jpg")

    fun peekMemory(item: MediaItem): Bitmap? = memory.get(key(item))

    /** The cached thumbnail from memory or disk. Never touches the network. */
    suspend fun peek(item: MediaItem): Bitmap? {
        val inMemory = peekMemory(item)
        if (inMemory != null) return inMemory
        return withContext(Dispatchers.IO) {
            val file = fileFor(item)
            if (!file.exists()) return@withContext null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) memory.put(key(item), bitmap)
            bitmap
        }
    }

    /**
     * The thumbnail, created from the server when it is not cached yet. Waiting in the queue is
     * cancellable, so scrolling quickly does not pile up work.
     */
    suspend fun load(server: SmbServer, item: MediaItem): Bitmap? {
        val cached = peek(item)
        if (cached != null) return cached
        if (!supports(item) || noThumbnail.contains(key(item))) return null
        val gate = if (item.isVideo) videoGate else imageGate
        return gate.withPermit {
            // It may have been created while this request was waiting.
            val existing = peek(item)
            if (existing != null) {
                existing
            } else {
                withContext(Dispatchers.IO) { generate(server, item) }
            }
        }
    }

    private fun generate(server: SmbServer, item: MediaItem): Bitmap? {
        val k = key(item)
        val marker = File(dir, "$k.none")
        if (marker.exists()) {
            noThumbnail.add(k)
            return null
        }

        val handle = smbManager.openFileHandle(server, item.path).getOrNull() ?: return null
        val bitmap: Bitmap? = try {
            if (item.isVideo) videoFrame(handle) else imageThumbnail(handle)
        } catch (e: Exception) {
            // Network or decoder problem: do not remember it, a later attempt may work.
            Log.w(TAG, "Thumbnail failed for ${item.name}: ${e.message}")
            return null
        } finally {
            handle.close()
        }

        if (bitmap == null) {
            noThumbnail.add(k)
            try {
                marker.createNewFile()
            } catch (_: Exception) {
            }
            return null
        }

        try {
            val target = fileFor(item)
            val temp = File(dir, "$k.tmp")
            FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            if (!temp.renameTo(target)) temp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Could not save thumbnail: ${e.message}")
        }
        memory.put(k, bitmap)
        return bitmap
    }

    private fun imageThumbnail(handle: SmbFileHandle): Bitmap? {
        val bytes = handle.readFully(MAX_IMAGE_BYTES) ?: return null
        return ImageDecoding.decode(bytes, THUMB_SIZE)
    }

    private fun videoFrame(handle: SmbFileHandle): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(SmbMediaDataSource(handle))
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // A frame a little way in is usually more telling than the (often black) first one.
            val targetUs = if (durationMs > 0) minOf(durationMs / 10, 120_000L) * 1000L else 0L
            var frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame == null && targetUs != 0L) {
                frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            return frame?.let { scaleDown(it, THUMB_SIZE) }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * scale).roundToInt()),
            max(1, (src.height * scale).roundToInt()),
            true
        )
    }

    /** Deletes the oldest thumbnails when the cache grows beyond its limit. */
    fun trimDiskCache() {
        try {
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_DISK_BYTES) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= TRIM_TO_BYTES) break
                total -= file.length()
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Trim failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "HaminaThumbs"
        const val THUMB_SIZE = 480
        const val MEMORY_BYTES = 48 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 48L * 1024 * 1024
        const val MAX_DISK_BYTES = 250L * 1024 * 1024
        const val TRIM_TO_BYTES = 200L * 1024 * 1024
    }
}
