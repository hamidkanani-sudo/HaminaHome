package com.hamina.home.util

/** MIME type guessed from the file extension (enough for the video / image / audio checks). */
fun guessMimeType(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
        lower.endsWith(".mkv") -> "video/x-matroska"
        lower.endsWith(".avi") -> "video/x-msvideo"
        lower.endsWith(".mov") -> "video/quicktime"
        lower.endsWith(".wmv") -> "video/x-ms-wmv"
        lower.endsWith(".flv") -> "video/x-flv"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".ts") || lower.endsWith(".m2ts") -> "video/mp2t"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".jfif") -> "image/jpeg"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".bmp") -> "image/bmp"
        lower.endsWith(".heic") -> "image/heic"
        lower.endsWith(".heif") -> "image/heif"
        lower.endsWith(".avif") -> "image/avif"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".flac") -> "audio/flac"
        lower.endsWith(".wav") -> "audio/wav"
        lower.endsWith(".aac") -> "audio/aac"
        lower.endsWith(".ogg") -> "audio/ogg"
        lower.endsWith(".m4a") -> "audio/mp4"
        else -> "application/octet-stream"
    }
}
