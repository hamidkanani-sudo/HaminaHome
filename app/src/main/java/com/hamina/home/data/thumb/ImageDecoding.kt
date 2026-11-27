package com.hamina.home.data.thumb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes images from memory, scaled so the longest side is at most `maxSide`.
 * On Android 9+ the system ImageDecoder is used, which also handles HEIC/HEIF (and AVIF on
 * Android 12+) and applies the EXIF rotation. Older systems fall back to BitmapFactory.
 */
object ImageDecoding {

    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > maxSide) {
                        val scale = maxSide.toFloat() / longest
                        decoder.setTargetSize(
                            max(1, (w * scale).roundToInt()),
                            max(1, (h * scale).roundToInt())
                        )
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                }
            } catch (_: Throwable) {
                // fall through to the legacy decoder
            }
        }
        return decodeLegacy(bytes, maxSide)
    }

    /** Original pixel size (width, height) read from the header, or null if unknown. */
    fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) Pair(opts.outWidth, opts.outHeight) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeLegacy(bytes: ByteArray, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

            val longest = max(bitmap.width, bitmap.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                bitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    max(1, (bitmap.width * scale).roundToInt()),
                    max(1, (bitmap.height * scale).roundToInt()),
                    true
                )
            }
            applyExifOrientation(bytes, bitmap)
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        return try {
            val exif = android.media.ExifInterface(ByteArrayInputStream(bytes))
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Throwable) {
            bitmap
        }
    }
}
