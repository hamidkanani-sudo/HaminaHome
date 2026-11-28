package com.hamina.home.data.thumb

import android.media.MediaDataSource
import com.hamina.home.data.smb.FileHandle
import java.io.IOException

/**
 * Lets MediaMetadataRetriever read a video straight from an SMB share. Reads are served from
 * a small cache of 256 KB chunks, because the retriever jumps between the file header, the
 * index and the data with many tiny reads, and every SMB round-trip is slow.
 */
class SmbMediaDataSource(private val handle: FileHandle) : MediaDataSource() {

    private val chunks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            return size > MAX_CHUNKS
        }
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val fileSize = handle.size
        if (position >= fileSize) return -1
        val limit = minOf(size.toLong(), fileSize - position).toInt()
        var written = 0
        var pos = position
        while (written < limit) {
            val index = pos / CHUNK_SIZE
            val chunk = chunks[index] ?: loadChunk(index).also { chunks[index] = it }
            val inChunk = (pos - index * CHUNK_SIZE).toInt()
            val n = minOf(chunk.size - inChunk, limit - written)
            if (n <= 0) break
            System.arraycopy(chunk, inChunk, buffer, offset + written, n)
            written += n
            pos += n
        }
        return if (written == 0) -1 else written
    }

    override fun getSize(): Long = handle.size

    override fun close() {
        handle.close()
    }

    private fun loadChunk(index: Long): ByteArray {
        val start = index * CHUNK_SIZE
        val length = minOf(CHUNK_SIZE.toLong(), handle.size - start).toInt()
        if (length <= 0) throw IOException("Chunk is beyond the end of the file")
        val data = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = handle.read(start + got, data, got, length - got)
            if (n <= 0) break
            got += n
        }
        return if (got == length) data else data.copyOf(got)
    }

    private companion object {
        const val CHUNK_SIZE = 256 * 1024
        const val MAX_CHUNKS = 16
    }
}
