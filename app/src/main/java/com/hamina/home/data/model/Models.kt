package com.hamina.home.data.model

import java.io.Serializable

/**
 * Represents a configured SMB server / share.
 */
data class SmbServer(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = 445,
    val isFavorite: Boolean = false,
    val isLocal: Boolean = false      // local storage volume (internal / USB), not an SMB server
) : Serializable

/**
 * Represents a file or folder entry from SMB or local.
 */
data class MediaItem(
    val id: String,
    val name: String,
    val path: String,                 // full path relative to share or absolute
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val mimeType: String? = null,
    val serverId: String? = null,     // which SmbServer it belongs to
    val thumbnailPath: String? = null, // local cached thumbnail path if available
    val lastPlayedPosition: Long = 0L,
    val lastPlayedAt: Long = 0L
) : Serializable {

    val isVideo: Boolean
        get() = mimeType?.startsWith("video/") == true ||
                name.lowercase().let {
                    it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".avi") ||
                    it.endsWith(".mov") || it.endsWith(".wmv") || it.endsWith(".flv") ||
                    it.endsWith(".webm") || it.endsWith(".m4v") || it.endsWith(".ts") ||
                    it.endsWith(".m2ts")
                }

    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true ||
                name.lowercase().let {
                    it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") ||
                    it.endsWith(".gif") || it.endsWith(".webp") || it.endsWith(".bmp") ||
                    it.endsWith(".heic") || it.endsWith(".heif") ||
                    it.endsWith(".avif") || it.endsWith(".jfif")
                }

    val isAudio: Boolean
        get() = mimeType?.startsWith("audio/") == true ||
                name.lowercase().let {
                    it.endsWith(".mp3") || it.endsWith(".flac") || it.endsWith(".wav") ||
                    it.endsWith(".aac") || it.endsWith(".ogg") || it.endsWith(".m4a")
                }
}

/**
 * Sort options requested by user.
 */
enum class SortMode {
    NAME_ASC,
    NAME_DESC,
    DATE_ASC,
    DATE_DESC,
    SIZE_ASC,
    SIZE_DESC,
    LAST_PLAYED          // most recently played first
}

/**
 * View mode for the browser.
 */
enum class ViewMode {
    LIST,
    GRID
}

/**
 * Global player settings that apply to all videos.
 */
data class PlayerSettings(
    val subtitleSize: Float = 1.0f,          // relative scale
    val subtitleColor: Long = 0xFFFFFFFF,    // ARGB
    val subtitlePosition: Float = 0.9f,      // 0.0 top → 1.0 bottom
    val subtitleOffsetMs: Long = 0L,         // global default offset
    val subtitleStyle: Int = 0,              // 0 outline, 1 shadow, 2 background box, 3 plain
    val aspectRatio: AspectRatio = AspectRatio.FIT
)

enum class AspectRatio {
    FIT,      // fit to screen keeping ratio
    FILL,     // fill screen (may crop)
    STRETCH,  // stretch to fill
    ORIGINAL  // 1:1 pixel
}
