package com.hamina.home.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import com.hamina.home.HaminaApp
import com.hamina.home.data.model.SmbServer
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Finds subtitle files that sit next to a video ("Movie.mkv" -> "Movie.srt", "Movie.fa.srt", ...)
 * and prepares them for the player.
 *
 * ExoPlayer reads text subtitles as UTF-8 only, but many Persian/Arabic .srt files are saved as
 * Windows-1256, which shows up as garbage. Each file is therefore converted to UTF-8 (into the
 * app cache) before it is handed to the player.
 */
object SubtitleSupport {

    private const val TAG = "HaminaSubs"
    private const val MAX_SUBTITLE_BYTES = 5L * 1024 * 1024
    private const val MAX_FILES = 6

    private val mimeByExtension = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "vtt" to MimeTypes.TEXT_VTT,
        "ass" to MimeTypes.TEXT_SSA,
        "ssa" to MimeTypes.TEXT_SSA
    )

    /** Blocking; run it on a background thread. */
    fun findSidecars(
        context: Context,
        server: SmbServer,
        videoPath: String
    ): List<androidx.media3.common.MediaItem.SubtitleConfiguration> {
        return try {
            val app = HaminaApp.instance
            val parent = videoPath.substringBeforeLast('/', "")
            val videoName = videoPath.substringAfterLast('/')
            val base = videoName.substringBeforeLast('.').lowercase()

            val listing = kotlinx.coroutines.runBlocking {
                app.smbManager.listDirectory(server, parent)
            }.getOrNull() ?: return emptyList()

            val candidates = listing
                .filter { !it.isDirectory }
                .filter {
                    val lower = it.name.lowercase()
                    mimeByExtension.containsKey(lower.substringAfterLast('.', "")) &&
                        lower.startsWith(base)
                }
                .sortedBy { it.name.lowercase() }
                .take(MAX_FILES)

            val dir = File(context.cacheDir, "subs").apply { mkdirs() }
            candidates.mapNotNull { item ->
                try {
                    val ext = item.name.substringAfterLast('.', "").lowercase()
                    val handle = app.smbManager.openFileHandle(server, item.path).getOrNull()
                        ?: return@mapNotNull null
                    val bytes: ByteArray? = try {
                        handle.readFully(MAX_SUBTITLE_BYTES)
                    } finally {
                        handle.close()
                    }
                    if (bytes == null) return@mapNotNull null

                    val text = decode(bytes)
                    val key = hash("${server.id}|${item.path}|${item.size}|${item.lastModified}")
                    val file = File(dir, "$key.$ext")
                    file.writeText(text, Charsets.UTF_8)

                    androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                        .setMimeType(mimeByExtension.getValue(ext))
                        .setLanguage(guessLanguage(item.name))
                        .setLabel(item.name)
                        .setSelectionFlags(0)
                        .build()
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping subtitle ${item.name}: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle search failed: ${e.message}")
            emptyList()
        }
    }

    /** UTF-8 / UTF-16 (with BOM) are kept; otherwise Windows-1256 for Arabic/Persian, else 1252. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            // not UTF-8
        }
        val arabic = String(bytes, Charset.forName("windows-1256"))
        val high = arabic.count { it.code > 127 }
        val arabicLetters = arabic.count { it.code in 0x0600..0x06FF }
        return if (high > 0 && arabicLetters * 2 >= high) {
            arabic
        } else {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun guessLanguage(name: String): String? {
        val lower = name.lowercase()
        return when {
            Regex("[._\\- ](fa|per|fas|persian|farsi)[._\\- ]").containsMatchIn(lower) -> "fa"
            Regex("[._\\- ](en|eng|english)[._\\- ]").containsMatchIn(lower) -> "en"
            Regex("[._\\- ](ar|ara|arabic)[._\\- ]").containsMatchIn(lower) -> "ar"
            else -> null
        }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
}
