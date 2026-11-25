package com.hamina.home.data.smb

import android.content.Context
import android.util.Log
import com.hamina.home.data.cache.CacheManager
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level SMB manager optimized for Android TV media browsing.
 * Uses smbj (SMB2/3) and integrates with local CacheManager for speed.
 */
class SmbManager(
    private val context: Context,
    private val cacheManager: CacheManager
) {

    private val client: SMBClient by lazy {
        val config = SmbConfig.builder()
            .withTimeout(15_000)           // 15s connection timeout
            .withSoTimeout(30_000)
            .withDfsEnabled(false)
            .build()
        SMBClient(config)
    }

    // Keep a few open sessions to avoid reconnecting on every folder open
    private val sessions = ConcurrentHashMap<String, Pair<Connection, Session>>()

    companion object {
        private const val TAG = "HaminaSMB"
    }

    /**
     * List directory contents. Tries cache first, then network.
     * Returns items sorted by name by default (caller can re-sort).
     */
    suspend fun listDirectory(
        server: SmbServer,
        path: String = "",
        forceRefresh: Boolean = false
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            // 1. Try local cache first (unless forced)
            if (!forceRefresh) {
                val cached = cacheManager.loadFolderListing(server.id, path)
                if (cached != null) {
                    Log.d(TAG, "Using cache for ${server.name}/$path")
                    return@withContext Result.success(cached)
                }
            }

            // 2. Connect / reuse session
            val share = connectShare(server)
                ?: return@withContext Result.failure(Exception("Could not connect to ${server.host}"))

            // 3. List remote directory
            val remotePath = path.trimStart('/').replace('\\', '/')
            val files = share.list(remotePath)

            val items = files
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { f ->
                    val fullPath = if (remotePath.isEmpty()) f.fileName
                                   else "$remotePath/${f.fileName}"
                    val isDir = f.fileAttributes.toInt() and 0x10 != 0   // DIRECTORY attribute
                    MediaItem(
                        id = UUID.nameUUIDFromBytes("${server.id}|$fullPath".toByteArray()).toString(),
                        name = f.fileName,
                        path = fullPath,
                        isDirectory = isDir,
                        size = if (isDir) 0L else f.endOfFile,
                        lastModified = f.lastWriteTime.toEpochMillis(),
                        serverId = server.id,
                        mimeType = if (isDir) null else guessMime(f.fileName)
                    )
                }
                .sortedWith(compareBy<MediaItem> { !it.isDirectory }.thenBy { it.name.lowercase() })

            // 4. Cache the result for next time
            cacheManager.saveFolderListing(server.id, path, items)

            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "listDirectory failed for ${server.host}/$path", e)
            Result.failure(e)
        }
    }

    /**
     * Open a readable InputStream for a remote file (used by the player).
     * The caller is responsible for closing the stream.
     */
    suspend fun openFileStream(server: SmbServer, path: String): Result<java.io.InputStream> =
        withContext(Dispatchers.IO) {
            try {
                val share = connectShare(server)
                    ?: return@withContext Result.failure(Exception("No connection"))

                val cleanPath = path.trimStart('/').replace('\\', '/')
                val file = share.openFile(
                    cleanPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                Result.success(file.inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "openFileStream failed", e)
                Result.failure(e)
            }
        }

    /**
     * Rename a file or folder.
     */
    suspend fun rename(server: SmbServer, oldPath: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val share = connectShare(server)
                    ?: return@withContext Result.failure(Exception("No connection"))
                val parent = oldPath.substringBeforeLast('/', "")
                val newPath = if (parent.isEmpty()) newName else "$parent/$newName"
                share.rename(oldPath.trimStart('/'), newPath)
                // Invalidate cache for parent folder
                cacheManager.saveFolderListing(server.id, parent, emptyList()) // force refresh next time
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Delete a file or empty folder.
     */
    suspend fun delete(server: SmbServer, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val share = connectShare(server)
                    ?: return@withContext Result.failure(Exception("No connection"))
                val clean = path.trimStart('/')
                // Try as file first, then directory
                try {
                    share.rm(clean)
                } catch (_: Exception) {
                    share.rmdir(clean, false)
                }
                val parent = path.substringBeforeLast('/', "")
                cacheManager.saveFolderListing(server.id, parent, emptyList())
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // -------------------- internal helpers --------------------

    private fun connectShare(server: SmbServer): DiskShare? {
        val key = server.id
        try {
            // Reuse existing session if still alive
            sessions[key]?.let { (conn, sess) ->
                if (conn.isConnected) {
                    return sess.connectShare(server.share) as? DiskShare
                } else {
                    sessions.remove(key)
                }
            }

            val connection = client.connect(server.host, server.port)
            val auth = if (server.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(server.username, server.password.toCharArray(), server.domain)
            }
            val session = connection.authenticate(auth)
            sessions[key] = connection to session
            return session.connectShare(server.share) as? DiskShare
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${server.host}", e)
            sessions.remove(key)
            return null
        }
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".flac") -> "audio/flac"
            else -> "application/octet-stream"
        }
    }

    fun closeAll() {
        sessions.values.forEach { (conn, _) ->
            try { conn.close() } catch (_: Exception) {}
        }
        sessions.clear()
        client.close()
    }
}
