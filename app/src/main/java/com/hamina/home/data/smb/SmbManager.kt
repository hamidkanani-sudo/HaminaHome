package com.hamina.home.data.smb

import android.content.Context
import android.util.Log
import com.hamina.home.data.cache.CacheManager
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
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

            // No share configured and at root → list available shares
            if (server.share.isBlank() && path.trim('/').isEmpty()) {
                val shares = listServerShares(server)
                if (shares.isEmpty()) {
                    return@withContext Result.failure(
                        Exception(
                            "No shares found. Enter a Share name when adding the server, or check account permissions."
                        )
                    )
                }
                cacheManager.saveFolderListing(server.id, path, shares)
                return@withContext Result.success(shares)
            }

            val resolved = resolveShareAndPath(server, path)
            if (resolved == null) {
                return@withContext Result.failure(Exception("Could not resolve share for path: $path"))
            }
            val shareName = resolved.first
            val remotePath = resolved.second

            val share = connectShare(server, shareName)
                ?: return@withContext Result.failure(
                    Exception("Could not connect to ${server.host}/$shareName")
                )

            share.use { diskShare ->
                val files = diskShare.list(remotePath)

                val items = files
                    .filter { f ->
                        f.fileName != "." && f.fileName != ".." && !f.fileName.startsWith(".")
                    }
                    .map { f ->
                        val isDir = f.fileAttributes.contains(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                        val childPath = buildChildPath(server, shareName, remotePath, f.fileName)
                        MediaItem(
                            id = UUID.randomUUID().toString(),
                            name = f.fileName,
                            path = childPath,
                            isDirectory = isDir,
                            size = if (isDir) 0L else f.endOfFile,
                            lastModified = try {
                                f.lastWriteTime.toEpochMillis()
                            } catch (_: Exception) {
                                0L
                            },
                            serverId = server.id,
                            mimeType = if (isDir) null else guessMime(f.fileName)
                        )
                    }
                    .sortedWith(
                        compareBy<MediaItem> { !it.isDirectory }.thenBy { it.name.lowercase() }
                    )

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
                val resolved = resolveShareAndPath(server, path)
                    ?: return@withContext Result.failure(Exception("No connection"))
                val shareName = resolved.first
                val cleanPath = resolved.second
                val share = connectShare(server, shareName)
                    ?: return@withContext Result.failure(Exception("No connection"))

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

    suspend fun rename(server: SmbServer, oldPath: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val resolved = resolveShareAndPath(server, oldPath)
                    ?: return@withContext Result.failure(Exception("No connection"))
                val shareName = resolved.first
                val relPath = resolved.second
                val share = connectShare(server, shareName)
                    ?: return@withContext Result.failure(Exception("No connection"))

                val cleanOld = relPath.replace('/', '\\')
                val parent = cleanOld.substringBeforeLast('\\', "")
                val newPath = if (parent.isEmpty()) newName else "$parent\\$newName"

                val entry = try {
                    share.openFile(
                        cleanOld,
                        EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    )
                } catch (_: Exception) {
                    share.openDirectory(
                        cleanOld,
                        EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    )
                }
                entry.rename(newPath)
                entry.close()

                val parentPath = if (server.share.isBlank()) {
                    val slash = oldPath.trim('/').indexOf('/')
                    if (slash < 0) "" else oldPath.trim('/').substring(0, slash)
                } else {
                    oldPath.substringBeforeLast('/', "")
                }
                cacheManager.saveFolderListing(server.id, parentPath, emptyList())
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun delete(server: SmbServer, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val resolved = resolveShareAndPath(server, path)
                    ?: return@withContext Result.failure(Exception("No connection"))
                val shareName = resolved.first
                val clean = resolved.second
                val share = connectShare(server, shareName)
                    ?: return@withContext Result.failure(Exception("No connection"))

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

    /**
     * If server.share is set, use it and treat [path] as path inside that share.
     * If server.share is blank, first path segment is the share name.
     */
    private fun resolveShareAndPath(server: SmbServer, path: String): Pair<String, String>? {
        val clean = path.trim('/').replace('\\', '/')
        if (server.share.isNotBlank()) {
            return Pair(server.share, clean)
        }
        if (clean.isEmpty()) return null
        val slash = clean.indexOf('/')
        return if (slash < 0) {
            Pair(clean, "")
        } else {
            Pair(clean.substring(0, slash), clean.substring(slash + 1))
        }
    }

    private fun buildChildPath(
        server: SmbServer,
        shareName: String,
        remotePath: String,
        fileName: String
    ): String {
        return when {
            server.share.isBlank() && remotePath.isEmpty() -> "$shareName/$fileName"
            server.share.isBlank() -> "$shareName/$remotePath/$fileName".replace("//", "/")
            remotePath.isEmpty() -> fileName
            else -> "$remotePath/$fileName"
        }.replace("//", "/")
    }

    private fun getSession(server: SmbServer): Session? {
        val key = server.id
        return try {
            val existing = sessions[key]
            if (existing != null && existing.first.isConnected) {
                existing.second
            } else {
                val conn = client.connect(server.host, server.port)
                val auth = if (server.username.isBlank()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        server.username,
                        server.password.toCharArray(),
                        server.domain
                    )
                }
                val sess = conn.authenticate(auth)
                sessions[key] = Pair(conn, sess)
                sess
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to authenticate to ${server.host}", e)
            sessions.remove(key)
            null
        }
    }

    private fun connectShare(server: SmbServer, shareName: String? = null): DiskShare? {
        val name = shareName ?: server.share
        if (name.isBlank()) return null
        return try {
            val session = getSession(server) ?: return null
            session.connectShare(name) as? DiskShare
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect share $name on ${server.host}", e)
            null
        }
    }

    /** List disk shares when user left Share field empty. */
    private fun listServerShares(server: SmbServer): List<MediaItem> {
        val session = getSession(server) ?: return emptyList()
        val names = mutableListOf<String>()

        try {
            val method = session.javaClass.methods.firstOrNull {
                it.name == "listShares" && it.parameterCount == 0
            }
            if (method != null) {
                val result = method.invoke(session)
                if (result is Collection<*>) {
                    for (item in result) {
                        val n = when (item) {
                            is String -> item
                            else -> {
                                try {
                                    item?.javaClass?.getMethod("getShareName")?.invoke(item) as? String
                                } catch (_: Exception) {
                                    item?.toString()
                                }
                            }
                        }
                        if (!n.isNullOrBlank()) names.add(n)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listShares reflection failed", e)
        }

        if (names.isEmpty()) {
            val candidates = listOf(
                "Videos", "Movies", "Music", "Photos", "Pictures", "Shared",
                "Users", "Public", "Share", "media", "Media", "homes", "Data", "TV"
            )
            for (c in candidates) {
                try {
                    val s = session.connectShare(c) as? DiskShare
                    if (s != null) {
                        names.add(c)
                        try {
                            s.close()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        return names
            .filter { !it.endsWith("$") }
            .distinct()
            .sortedBy { it.lowercase() }
            .map { shareName ->
                MediaItem(
                    id = UUID.randomUUID().toString(),
                    name = shareName,
                    path = shareName,
                    isDirectory = true,
                    serverId = server.id
                )
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
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
        sessions.clear()
        client.close()
    }
}
