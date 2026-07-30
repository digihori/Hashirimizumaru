package tk.horiuchi.hashirimizumaru

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max

data class ImportedCatchPhoto(
    val relativePath: String,
    val takenAt: Long?,
    val latitude: Double?,
    val longitude: Double?
)

class CatchPhotoRepository(private val context: Context) {
    suspend fun import(uri: Uri, allowLocationMetadata: Boolean): ImportedCatchPhoto =
        withContext(Dispatchers.IO) {
            val readableUri = if (Build.VERSION.SDK_INT >= 29 && allowLocationMetadata) {
                runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            } else {
                uri
            }
            val metadata = runCatching { readMetadata(readableUri) }
                .getOrElse { error ->
                    if (readableUri != uri) readMetadata(uri) else throw error
                }
            val bitmap = runCatching {
                decodeScaledBitmap(readableUri, metadata.orientation)
            }.getOrElse { error ->
                if (readableUri != uri) {
                    decodeScaledBitmap(uri, metadata.orientation)
                } else {
                    throw error
                }
            }
            val directory = File(context.filesDir, PHOTO_DIRECTORY).apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.jpg")
            try {
                file.outputStream().buffered().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                        "写真を保存できませんでした"
                    }
                }
            } catch (error: Throwable) {
                file.delete()
                throw error
            } finally {
                bitmap.recycle()
            }
            ImportedCatchPhoto(
                relativePath = "$PHOTO_DIRECTORY/${file.name}",
                takenAt = metadata.takenAt,
                latitude = metadata.latitude,
                longitude = metadata.longitude
            )
        }

    fun file(relativePath: String?): File? =
        relativePath
            ?.takeIf { it.startsWith("$PHOTO_DIRECTORY/") }
            ?.let { File(context.filesDir, it) }
            ?.takeIf { it.isFile }

    fun delete(relativePath: String?) {
        file(relativePath)?.delete()
    }

    private fun readMetadata(uri: Uri): PhotoMetadata {
        val exif = context.contentResolver.openInputStream(uri)?.use(::ExifInterface)
            ?: error("写真を開けませんでした")
        val coordinates = exif.latLong
        return PhotoMetadata(
            takenAt = parseExifDateTime(exif),
            latitude = coordinates?.getOrNull(0),
            longitude = coordinates?.getOrNull(1),
            orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        )
    }

    private fun decodeScaledBitmap(uri: Uri, orientation: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "画像形式を読み取れませんでした" }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_EDGE * 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("写真を読み込めませんでした")
        val oriented = orient(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()
        val longestEdge = max(oriented.width, oriented.height)
        if (longestEdge <= MAX_IMAGE_EDGE) return oriented
        val ratio = MAX_IMAGE_EDGE.toFloat() / longestEdge
        val scaled = Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * ratio).toInt(),
            (oriented.height * ratio).toInt(),
            true
        )
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun parseExifDateTime(exif: ExifInterface): Long? {
        val value = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
            ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        return runCatching {
            if (offset != null) {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US).apply {
                    isLenient = false
                }.parse(value + offset)?.time
            } else {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getDefault()
                }.parse(value)?.time
            }
        }.getOrNull()
    }

    private data class PhotoMetadata(
        val takenAt: Long?,
        val latitude: Double?,
        val longitude: Double?,
        val orientation: Int
    )

    companion object {
        private const val PHOTO_DIRECTORY = "catch_photos"
        private const val MAX_IMAGE_EDGE = 1800
    }
}
