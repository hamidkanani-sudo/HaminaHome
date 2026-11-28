package com.hamina.home.data.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hamina.home.data.model.SmbServer
import java.io.IOException

/**
 * Media3 DataSource that streams a file from an SMB share.
 *
 * - Reports the real file length, so ExoPlayer can seek (MP4 with moov at the end, MKV cues, ...).
 * - Reads directly at the requested offset instead of skipping through a stream.
 * - Reads in large chunks to avoid many tiny SMB round-trips.
 *
 * URI format: smb://host/<path as returned in MediaItem.path, percent-encoded>.
 * Use [buildUri] to create it so special characters (#, ?, %, spaces, Persian names) are safe.
 */
@UnstableApi
class SmbDataSource(
    private val smbManager: SmbManager,
    private val server: SmbServer
) : BaseDataSource(/* isNetwork = */ true) {

    private var handle: FileHandle? = null
    private var uri: Uri? = null
    private var opened = false

    private var position = 0L        // file offset of the next unread byte
    private var bytesRemaining = 0L

    private val buffer = ByteArray(BUFFER_SIZE)
    private var bufferLength = 0
    private var bufferPos = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val path = pathFromUri(dataSpec.uri)
            ?: throw IOException("Invalid SMB URI: ${dataSpec.uri}")

        val h = smbManager.openFileHandle(server, path)
            .getOrElse { throw IOException("SMB open failed: ${it.message}", it) }

        val size = h.size
        if (dataSpec.position > size) {
            h.close()
            throw IOException("Requested position ${dataSpec.position} is beyond file size $size")
        }
        val remainingInFile = size - dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, remainingInFile)
        } else {
            remainingInFile
        }

        handle = h
        position = dataSpec.position
        bufferLength = 0
        bufferPos = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val h = handle ?: throw IOException("SMB file is not open")

        if (bufferPos >= bufferLength) {
            val toFetch = minOf(BUFFER_SIZE.toLong(), bytesRemaining).toInt()
            val n = h.read(position, buffer, 0, toFetch)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            bufferLength = n
            bufferPos = 0
        }

        val available = bufferLength - bufferPos
        val n = minOf(length, available, minOf(bytesRemaining, Int.MAX_VALUE.toLong()).toInt())
        System.arraycopy(buffer, bufferPos, target, offset, n)
        bufferPos += n
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            handle?.close()
        } finally {
            handle = null
            bufferLength = 0
            bufferPos = 0
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val smbManager: SmbManager,
        private val server: SmbServer
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(smbManager, server)
    }

    companion object {
        private const val BUFFER_SIZE = 512 * 1024

        /** Builds a safe media URI for a MediaItem.path of the given server. */
        fun buildUri(server: SmbServer, path: String): Uri {
            val builder = Uri.Builder().scheme("smb").authority(server.host)
            path.replace('\\', '/')
                .split('/')
                .filter { it.isNotEmpty() }
                .forEach { builder.appendPath(it) }
            return builder.build()
        }

        private fun pathFromUri(uri: Uri): String? {
            val segments = uri.pathSegments
            if (segments.isEmpty()) return null
            return segments.joinToString("/")
        }
    }
}
