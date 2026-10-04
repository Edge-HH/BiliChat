package com.bilichat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

data class PreparedImage(
    val bytes: ByteArray,
    val mimeType: String,
    /** 私信图片消息中的 imageType，如 jpeg、png、gif。 */
    val imageType: String,
    val fileName: String,
)

/**
 * 读取用户选择的图片。B 站图床只接受 jpg/png/gif，其他格式或过大的图片会被转成 JPEG。
 */
class ImagePreparer(private val context: Context) {

    suspend fun prepare(uri: Uri): PreparedImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)?.lowercase() ?: "image/jpeg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("无法读取图片")
        val type = SUPPORTED[mimeType]
        if (type != null && bytes.size <= MAX_BYTES) {
            PreparedImage(bytes, mimeType, type, "image.$type")
        } else {
            PreparedImage(reencode(uri, bytes), "image/jpeg", "jpeg", "image.jpg")
        }
    }

    private fun reencode(uri: Uri, original: ByteArray): ByteArray {
        val bitmap = decode(uri, original) ?: throw IOException("不支持的图片格式")
        try {
            var quality = 90
            while (true) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_BYTES || quality <= 50) return out.toByteArray()
                quality -= 15
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(uri: Uri, bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 会按 EXIF 方向旋转，并支持 HEIF/WebP 等格式。
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_SIDE) {
                        val scale = MAX_SIDE.toFloat() / longest
                        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    }
                }
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        val SUPPORTED = mapOf("image/jpeg" to "jpeg", "image/jpg" to "jpeg", "image/png" to "png", "image/gif" to "gif")
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_SIDE = 4096
    }
}
