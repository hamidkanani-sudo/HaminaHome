package com.hamina.home.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.smb.FileHandle
import com.hamina.home.util.Ids
import com.hamina.home.util.guessMimeType
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A file opened from local storage. */
class LocalFileHandle(
    private val file: RandomAccessFile,
    override val size: Long
) : FileHandle() {

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        file.seek(position)
        return file.read(buffer, offset, length)
    }

    override fun close() {
        try {
            file.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Local storage: the TV's internal storage and connected USB drives / SD cards.
 * A volume is represented as an [SmbServer] with `isLocal = true` and the volume's root folder
 * in `host`, so the browser, player and viewers treat it like any other source.
 */
object LocalStorage {

    const val ID_PREFIX = "local:"

    class Volume(
        val server: SmbServer,
        val removable: Boolean,
        val freeBytes: Long,
        val totalBytes: Long
    )

    fun serverFor(root: File, name: String): SmbServer = SmbServer(
        id = ID_PREFIX + root.absolutePath,
        name = name,
        host = root.absolutePath,
        share = "",
        port = 0,
        isLocal = true
    )

    fun serverFromId(id: String): SmbServer {
        val root = File(id.removePrefix(ID_PREFIX))
        return serverFor(root, labelFor(root))
    }

    private fun labelFor(root: File): String =
        if (root.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
            "Internal storage"
        } else {
            root.name
        }

    fun fileOf(server: SmbServer, path: String): File {
        val clean = path.trim('/')
        return if (clean.isEmpty()) File(server.host) else File(server.host, clean)
    }

    /** Whether the app may read and write all files on shared / external storage. */
    fun hasAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private val hiddenNames = setOf("lost.dir", "system volume information", "thumbs.db", "desktop.ini")

    /** Lists a folder. Blocking. */
    fun list(server: SmbServer, path: String): List<MediaItem> {
        val dir = fileOf(server, path)
        val files = dir.listFiles()
            ?: throw IOException(
                "Cannot read this folder. Check the storage access permission, " +
                    "or whether the drive is still connected."
            )
        val base = path.trim('/')
        return files
            .filter { f ->
                val n = f.name
                !(n.startsWith(".") || n.startsWith("$") || hiddenNames.contains(n.lowercase())) &&
                    !(base.isEmpty() && n == "Android")
            }
            .map { f ->
                val childPath = if (base.isEmpty()) f.name else "$base/${f.name}"
                val isDir = f.isDirectory
                MediaItem(
                    id = Ids.mediaId(server.id, childPath),
                    name = f.name,
                    path = childPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else f.length(),
                    lastModified = f.lastModified(),
                    mimeType = if (isDir) null else guessMimeType(f.name),
                    serverId = server.id
                )
            }
    }

    fun open(server: SmbServer, path: String): Result<FileHandle> {
        return try {
            val file = fileOf(server, path)
            if (!file.isFile) return Result.failure(IOException("File not found: ${file.name}"))
            Result.success(LocalFileHandle(RandomAccessFile(file, "r"), file.length()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Internal storage and mounted removable volumes. */
    fun volumes(context: Context): List<Volume> {
        val out = ArrayList<Volume>()
        val seen = HashSet<String>()
        try {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (volume in manager.storageVolumes) {
                val state = volume.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = directoryOf(volume) ?: continue
                if (!seen.add(dir.absolutePath)) continue
                val removable = volume.isRemovable
                val name = if (volume.isPrimary && !removable) {
                    "Internal storage"
                } else {
                    volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: dir.name
                }
                out.add(makeVolume(dir, name, removable))
            }
        } catch (_: Exception) {
        }

        // Fallbacks for devices that hide volume paths.
        if (out.none { !it.removable }) {
            val internal = Environment.getExternalStorageDirectory()
            if (seen.add(internal.absolutePath)) out.add(0, makeVolume(internal, "Internal storage", false))
        }
        try {
            for (dir in context.getExternalFilesDirs(null)) {
                val path = dir?.absolutePath ?: continue
                val root = File(path.substringBefore("/Android/data"))
                if (root.absolutePath.length < 3 || !seen.add(root.absolutePath)) continue
                out.add(makeVolume(root, root.name, true))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun directoryOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return volume.directory
        return try {
            val path = StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
            path?.let { File(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun makeVolume(dir: File, name: String, removable: Boolean): Volume {
        var free = 0L
        var total = 0L
        try {
            val stat = StatFs(dir.absolutePath)
            free = stat.availableBytes
            total = stat.totalBytes
        } catch (_: Exception) {
        }
        return Volume(serverFor(dir, name), removable, free, total)
    }
}
