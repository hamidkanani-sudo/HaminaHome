package com.hamina.home.data.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hamina.home.data.model.SmbServer
import java.io.IOException
import java.io.InputStream

/**
 * Media3 DataSource that streams a file from an SMB share.
 * This allows ExoPlayer to play videos directly from the network share
 * without downloading the whole file first.
 */
@UnstableApi
class SmbDataSource(
    private val smbManager: SmbManager,
    private val server: SmbServer
) : BaseDataSource(/* isNetwork = */ true) {

    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        // Expected URI format: smb://host/share/path/to/file
        val path = extractPath(dataSpec.uri)
            ?: throw IOException("Invalid SMB URI: ${dataSpec.uri}")

        // We open the stream on a background thread via runBlocking-style call
        // because Media3 open() is allowed to block.
        val result = kotlinx.coroutines.runBlocking {
            smbManager.openFileStream(server, path)
        }
        val stream = result.getOrElse { throw IOException("SMB open failed", it) }

        // Handle position for seeking
        if (dataSpec.position > 0) {
            var skipped = 0L
            while (skipped < dataSpec.position) {
                val s = stream.skip(dataSpec.position - skipped)
                if (s <= 0) break
                skipped += s
            }
        }

        inputStream = stream
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
                     else minOf(length.toLong(), bytesRemaining).toInt()

        val read = inputStream?.read(buffer, offset, toRead) ?: -1
        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            inputStream?.close()
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun extractPath(uri: Uri): String? {
        // smb://host/share/folder/file.mkv  →  folder/file.mkv
        val path = uri.path ?: return null
        // path starts with /share/...
        val parts = path.trimStart('/').split("/", limit = 2)
        return if (parts.size >= 2) parts[1] else parts.getOrNull(0)
    }

    class Factory(
        private val smbManager: SmbManager,
        private val server: SmbServer
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return SmbDataSource(smbManager, server)
        }
    }
}
