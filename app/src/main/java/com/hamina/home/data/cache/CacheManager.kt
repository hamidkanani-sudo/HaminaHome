package com.hamina.home.data.cache

import android.content.Context
import android.util.Log
import com.hamina.home.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Local cache for folder listings and thumbnails.
 * This is critical for fast re-browsing of large folders (500–2000 files).
 */
class CacheManager(private val context: Context) {

    private val cacheRoot: File by lazy {
        File(context.cacheDir, "hamina_cache").also { it.mkdirs() }
    }

    private val folderCacheDir: File by lazy {
        File(cacheRoot, "folders").also { it.mkdirs() }
    }

    private val thumbnailCacheDir: File by lazy {
        File(cacheRoot, "thumbs").also { it.mkdirs() }
    }

    private val metaDir: File by lazy {
        File(cacheRoot, "meta").also { it.mkdirs() }
    }

    companion object {
        private const val TAG = "HaminaCache"
        private const val FOLDER_CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    }

    /**
     * Returns a stable key for a remote path on a specific server.
     */
    fun makeKey(serverId: String, path: String): String {
        val raw = "$serverId|$path"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    /**
     * Save a folder listing to local cache.
     */
    suspend fun saveFolderListing(serverId: String, path: String, items: List<MediaItem>) {
        withContext(Dispatchers.IO) {
            try {
                val key = makeKey(serverId, path)
                val file = File(folderCacheDir, "$key.json")
                val arr = JSONArray()
                items.forEach { item ->
                    arr.put(JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("path", item.path)
                        put("dir", item.isDirectory)
                        put("size", item.size)
                        put("mod", item.lastModified)
                        put("mime", item.mimeType ?: "")
                        put("server", item.serverId ?: "")
                        put("thumb", item.thumbnailPath ?: "")
                    })
                }
                val content = arr.toString()
                file.writeText(content)
                // Write timestamp
                File(metaDir, "$key.ts").writeText(System.currentTimeMillis().toString())
                Log.d(TAG, "Cached folder $path → ${items.size} items")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache folder listing", e)
            }
        }
    }

    /**
     * Load a previously cached folder listing if it is still fresh.
     */
    suspend fun loadFolderListing(serverId: String, path: String): List<MediaItem>? {
        return withContext(Dispatchers.IO) {
            try {
                val key = makeKey(serverId, path)
                val tsFile = File(metaDir, "$key.ts")
                if (!tsFile.exists()) return@withContext null

                val age = System.currentTimeMillis() - (tsFile.readText().toLongOrNull() ?: 0L)
                if (age > FOLDER_CACHE_TTL_MS) {
                    // Cache expired – caller should refresh
                    return@withContext null
                }

                val file = File(folderCacheDir, "$key.json")
                if (!file.exists()) return@withContext null

                // Files written by older versions used a different format; parsing fails and we treat that as a cache miss.
                val arr = JSONArray(file.readText())
                val items = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MediaItem(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        path = o.getString("path"),
                        isDirectory = o.optBoolean("dir", false),
                        size = o.optLong("size", 0L),
                        lastModified = o.optLong("mod", 0L),
                        mimeType = o.optString("mime", "").ifEmpty { null },
                        serverId = o.optString("server", "").ifEmpty { null },
                        thumbnailPath = o.optString("thumb", "").ifEmpty { null }
                    )
                }
                Log.d(TAG, "Loaded cached folder $path → ${items.size} items")
                items
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load folder cache", e)
                null
            }
        }
    }

    /** Drops the cached listing of one folder (used after rename / delete). */
    suspend fun invalidateFolder(serverId: String, path: String) {
        withContext(Dispatchers.IO) {
            val key = makeKey(serverId, path)
            File(folderCacheDir, "$key.json").delete()
            File(metaDir, "$key.ts").delete()
        }
    }

    /**
     * Path where a thumbnail for a given media item should be stored.
     */
    fun getThumbnailFile(serverId: String, path: String): File {
        val key = makeKey(serverId, path)
        return File(thumbnailCacheDir, "$key.jpg")
    }

    /**
     * Check if a local thumbnail already exists.
     */
    fun hasThumbnail(serverId: String, path: String): Boolean {
        return getThumbnailFile(serverId, path).exists()
    }

    /**
     * Clear all caches (useful after major network changes).
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            folderCacheDir.listFiles()?.forEach { it.delete() }
            thumbnailCacheDir.listFiles()?.forEach { it.delete() }
            metaDir.listFiles()?.forEach { it.delete() }
            Log.i(TAG, "All caches cleared")
        }
    }

    /**
     * Approximate size of the cache on disk.
     */
    fun getCacheSizeBytes(): Long {
        return (folderCacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()) +
               (thumbnailCacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum())
    }
}
