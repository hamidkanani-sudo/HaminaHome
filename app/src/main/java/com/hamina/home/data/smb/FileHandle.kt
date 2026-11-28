package com.hamina.home.data.smb

import java.io.Closeable
import java.io.InputStream

/** An open file (on an SMB share or on local storage) that can be read at any offset. */
abstract class FileHandle : Closeable {

    abstract val size: Long

    /** Reads up to [length] bytes at [position]. Returns -1 at the end of the file. */
    abstract fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

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

    /** Sequential stream view; closing the stream closes the file. */
    fun inputStream(): InputStream = object : InputStream() {
        private var pos = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = this@FileHandle.read(pos, b, off, minOf(len, 512 * 1024))
            if (n <= 0) return -1
            pos += n
            return n
        }

        override fun close() {
            this@FileHandle.close()
        }
    }
}
