package com.hamina.home.data.smb

import android.content.Context
import android.util.Log
import com.hamina.home.data.cache.CacheManager
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.util.Ids
import com.hamina.home.util.NaturalOrder
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
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
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * An open remote file. Supports reading at an arbitrary offset (needed for seeking in video)
 * and releases both the file handle and its share connection on [close].
 */
class SmbFileHandle internal constructor(
    private val share: DiskShare,
    private val file: SmbFile,
    val size: Long
) : Closeable {

    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        return try {
            file.read(buffer, position, offset, length)
        } catch (e: Exception) {
            throw IOException("SMB read failed: ${e.message}", e)
        }
    }

    /** Reads the whole file into memory. Returns null if it is larger than [maxBytes]. */
    fun readFully(maxBytes: Long): ByteArray? {
        if (size > maxBytes || size > Int.MAX_VALUE) return null
        val out = ByteArray(size.toInt())
        var pos = 0
        while (pos < out.size) {
            val n = read(pos.toLong(), out, pos, minOf(out.size - pos, 512 * 1024))
            if (n <= 0) break
            pos += n
        }
        return if (pos == out.size) out else null
    }

    /** Sequential stream view; closing the stream closes the file and share. */
    fun inputStream(): InputStream = object : InputStream() {
        private var pos = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = this@SmbFileHandle.read(pos, b, off, minOf(len, 512 * 1024))
            if (n <= 0) return -1
            pos += n
            return n
        }

        override fun close() {
            this@SmbFileHandle.close()
        }
    }

    override fun close() {
        try {
            file.close()
        } catch (_: Exception) {
        }
        try {
            share.close()
        } catch (_: Exception) {
        }
    }
}

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

    private class SessionEntry(
        val connection: Connection,
        val session: Session,
        val fingerprint: String
    )

    private val sessions = ConcurrentHashMap<String, SessionEntry>()
    private val sessionLock = Any()

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
                    .filter { f -> !isHiddenName(f.fileName) }
                    .map { f ->
                        // smbj exposes fileAttributes as a Long bitmask, not a Set
                        val isDir = (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                        val childPath = buildChildPath(server, shareName, remotePath, f.fileName)
                        MediaItem(
                            id = Ids.mediaId(server.id, childPath),
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
                        compareBy<MediaItem> { !it.isDirectory }
                            .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
                    )

                cacheManager.saveFolderListing(server.id, path, items)
                Result.success(items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "listDirectory failed for ${server.host}/$path", e)
            Result.failure(e)
        }
    }

    /**
     * Opens a remote file for random-access reading. This call blocks; run it off the main thread.
     * The caller must close the returned handle.
     */
    fun openFileHandle(server: SmbServer, path: String): Result<SmbFileHandle> {
        val resolved = resolveShareAndPath(server, path)
            ?: return Result.failure(IOException("Invalid path: $path"))
        val diskShare = connectShare(server, resolved.first)
            ?: return Result.failure(IOException("Could not connect to ${server.host}/${resolved.first}"))
        return try {
            val file = diskShare.openFile(
                resolved.second,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            try {
                val size = file.fileInformation.standardInformation.endOfFile
                Result.success(SmbFileHandle(diskShare, file, size))
            } catch (e: Exception) {
                try {
                    file.close()
                } catch (_: Exception) {
                }
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "openFileHandle failed for $path", e)
            try {
                diskShare.close()
            } catch (_: Exception) {
            }
            Result.failure(e)
        }
    }

    suspend fun openFileStream(server: SmbServer, path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            openFileHandle(server, path).map { it.inputStream() }
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

                try {
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
                    try {
                        entry.rename(newPath)
                    } finally {
                        try {
                            entry.close()
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    try {
                        share.close()
                    } catch (_: Exception) {
                    }
                }

                cacheManager.invalidateFolder(server.id, oldPath.substringBeforeLast('/', ""))
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
                    try {
                        share.rm(clean)
                    } catch (_: Exception) {
                        share.rmdir(clean, false)
                    }
                } finally {
                    try {
                        share.close()
                    } catch (_: Exception) {
                    }
                }
                cacheManager.invalidateFolder(server.id, path.substringBeforeLast('/', ""))
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

    private fun fingerprint(server: SmbServer): String =
        "${server.host}|${server.port}|${server.username}|${server.domain}|${server.password.hashCode()}"

    private fun getSession(server: SmbServer): Session? = synchronized(sessionLock) {
        val key = server.id
        try {
            val existing = sessions[key]
            if (existing != null &&
                existing.connection.isConnected &&
                existing.fingerprint == fingerprint(server)
            ) {
                return@synchronized existing.session
            }
            // Stale connection or the server settings were edited: drop the old session first.
            if (existing != null) {
                sessions.remove(key)
                try {
                    existing.connection.close()
                } catch (_: Exception) {
                }
            }
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
            sessions[key] = SessionEntry(conn, sess, fingerprint(server))
            sess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to authenticate to ${server.host}", e)
            sessions.remove(key)
            null
        }
    }

    /** Closes the cached connection of one server (e.g. after it is deleted). */
    fun evict(serverId: String) {
        synchronized(sessionLock) {
            sessions.remove(serverId)?.let {
                try {
                    it.connection.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Hides Windows system folders and dot-files. */
    private fun isHiddenName(name: String): Boolean {
        if (name == "." || name == ".." || name.startsWith(".") || name.startsWith("$")) return true
        return name.equals("System Volume Information", ignoreCase = true) ||
            name.equals("Thumbs.db", ignoreCase = true) ||
            name.equals("desktop.ini", ignoreCase = true)
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

        // Ask the server for its share list (MS-SRVS over the IPC$ pipe) instead of guessing names.
        try {
            val service = ServerService(SMBTransportFactories.SRVSVC.getTransport(session))
            for (info in service.getShares1()) {
                val shareName = info.netName
                // type 0 = disk share; ignore printers, devices, IPC and admin ($) shares
                if (!shareName.isNullOrBlank() && (info.type and 0x0FFFFFFF) == 0) {
                    names.add(shareName)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Share enumeration failed, falling back to common names", e)
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
                    id = Ids.mediaId(server.id, shareName),
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
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".heif") -> "image/heif"
            lower.endsWith(".avif") -> "image/avif"
            lower.endsWith(".jfif") -> "image/jpeg"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".flac") -> "audio/flac"
            else -> "application/octet-stream"
        }
    }

    fun closeAll() {
        sessions.values.forEach { entry ->
            try {
                entry.connection.close()
            } catch (_: Exception) {
            }
        }
        sessions.clear()
        client.close()
    }
}
