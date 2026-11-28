package com.hamina.home.data.storage

import com.hamina.home.data.local.LocalStorage
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.smb.FileHandle
import com.hamina.home.data.smb.SmbManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * File operations that work the same on SMB shares and on local storage.
 * Everything here blocks, so call it from a background thread.
 */
class StorageOps(private val smb: SmbManager) {

    fun list(server: SmbServer, path: String): List<MediaItem> {
        if (server.isLocal) return LocalStorage.list(server, path)
        return runBlocking { smb.listDirectory(server, path, true) }.getOrThrow()
    }

    fun openRead(server: SmbServer, path: String): FileHandle =
        smb.openFileHandle(server, path).getOrThrow()

    fun openWrite(server: SmbServer, path: String): OutputStream {
        if (server.isLocal) return FileOutputStream(LocalStorage.fileOf(server, path))
        return smb.openWriteStream(server, path)
    }

    fun mkdir(server: SmbServer, path: String) {
        if (server.isLocal) {
            val dir = LocalStorage.fileOf(server, path)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the folder")
        } else {
            smb.mkdir(server, path)
        }
    }

    fun delete(server: SmbServer, path: String, isDirectory: Boolean) {
        if (server.isLocal) {
            val file = LocalStorage.fileOf(server, path)
            if (file.exists() && !file.deleteRecursively()) {
                throw IOException("Could not delete ${file.name}")
            }
        } else {
            smb.deletePath(server, path, isDirectory)
        }
    }

    /** Moves/renames inside one source. Fails if the source cannot do it directly. */
    fun move(server: SmbServer, oldPath: String, newPath: String) {
        if (server.isLocal) {
            val from = LocalStorage.fileOf(server, oldPath)
            val to = LocalStorage.fileOf(server, newPath)
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) throw IOException("Could not move ${from.name}")
        } else {
            smb.movePath(server, oldPath, newPath)
        }
    }

    fun rename(server: SmbServer, path: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) {
            throw IOException("This name is not valid")
        }
        val parent = path.substringBeforeLast('/', "")
        val target = join(parent, clean)
        if (server.isLocal && File(LocalStorage.fileOf(server, target).path).exists()) {
            throw IOException("An item with this name already exists")
        }
        move(server, path, target)
    }

    fun join(dir: String, name: String): String =
        if (dir.isEmpty()) name else "${dir.trimEnd('/')}/$name"
}
