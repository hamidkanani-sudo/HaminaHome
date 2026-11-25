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
import java.util.concurrent.TimeUnit

class SmbManager(
    private val context: Context,
    private val cacheManager: CacheManager
) {

    private val client: SMBClient by lazy {
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withDfsEnabled(false)
            .build()
        SMBClient(config)
    }

    // ذخیره اتصال و نشست، DiskShare در هر بار فراخوانی به صورت جداگانه مدیریت می‌شود
    private val sessions = ConcurrentHashMap<String, Pair<Connection, Session>>()

    companion object {
        private const val TAG = "HaminaSMB"
    }

    suspend fun listDirectory(
        server: SmbServer,
        path: String = "",
        forceRefresh: Boolean = false
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            if (!forceRefresh) {
                val cached = cacheManager.loadFolderListing(server.id, path)
                if (cached != null) {
                    Log.d(TAG, "Using cache for ${server.name}/$path")
                    return@withContext Result.success(cached)
                }
            }

            val share = connectShare(server)
                ?: return@withContext Result.failure(Exception("Could not connect to ${server.host}"))

            // استفاده از use برای اطمینان از بسته شدن DiskShare
            share.use { diskShare ->
                val remotePath = path.trimStart('/').replace('\\', '/')
                val files = diskShare.list(remotePath)

                val items = files
                    .filter { it.fileName != "." && it.fileName != ".." }
                    .map { f ->
                        val fullPath = if (remotePath.isEmpty()) f.fileName
                                       else "$remotePath/${f.fileName}"
                        val isDir = f.fileAttributes.toInt() and 0x10 != 0
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

                cacheManager.saveFolderListing(server.id, path, items)
                Result.success(items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "listDirectory failed for ${server.host}/$path", e)
            Result.failure(e)
        }
    }

    suspend fun openFileStream(server: SmbServer, path: String): Result<java.io.InputStream> =
        withContext(Dispatchers.IO) {
            try {
                val share = connectShare(server)
                    ?: return@withContext Result.failure(Exception("No connection"))

                // توجه: inputStream باید تا زمان خواندن باز بماند، بنابراین نمی‌توان از use استفاده کرد
                val cleanPath = path.trimStart('/').replace('\\', '/')
                val file = share.openFile(
                    cleanPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                // DiskShare به صورت خودکار بسته نمی‌شود، اما جریان ورودی به آن وابسته است
                Result.success(file.inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "openFileStream failed", e)
                Result.failure(e)
            }
        }

    suspend fun rename(server: SmbServer, oldPath: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val share = connectShare(server)
                    ?: return@withContext Result.failure(Exception("No connection"))

                val cleanOld = oldPath.trimStart('/').replace('/', '\\')
                val parent = cleanOld.substringBeforeLast('\\', "")
                val newPath = if (parent.isEmpty()) newName else "$parent\\$newName"

                // تلاش برای باز کردن به عنوان فایل، در صورت شکست به عنوان دایرکتوری
                val entry = try {
                    share.openFile(
                        cleanOld,
                        EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    )
                } catch (e: Exception) {
                    share.openDirectory(
                        cleanOld,
                        EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    )
                }
                entry.rename(newPath, true)
                entry.close()

                cacheManager.saveFolderListing(server.id, parent.replace('\\', '/'), emptyList())
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun delete(server: SmbServer, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val share = connectShare(server)
                    ?: return@withContext Result.failure(Exception("No connection"))
                val clean = path.trimStart('/')
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

    private fun connectShare(server: SmbServer): DiskShare? {
        val key = server.id
        return try {
            val (connection, session) = sessions[key]?.takeIf { it.first.isConnected }
                ?: run {
                    // ایجاد اتصال جدید
                    val conn = client.connect(server.host, server.port)
                    val auth = if (server.username.isBlank()) {
                        AuthenticationContext.anonymous()
                    } else {
                        AuthenticationContext(server.username, server.password.toCharArray(), server.domain)
                    }
                    val sess = conn.authenticate(auth)
                    sessions[key] = conn to sess
                    conn to sess
                }

            session.connectShare(server.share) as? DiskShare
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${server.host}", e)
            sessions.remove(key)
            null
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