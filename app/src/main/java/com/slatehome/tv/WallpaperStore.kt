package com.slatehome.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.slatehome.tv.ui.Backdrop
import com.slatehome.tv.ui.WallpaperStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo the user added. Only its small thumbnail lives in memory. */
class UserPhoto(val file: File, val thumbnail: ImageBitmap) {
    val id: String get() = PHOTO_PREFIX + file.name
}

private const val PHOTO_PREFIX = "photo:"

/**
 * Which wallpaper is active, plus the user's own photos (saved under filesDir/wallpapers).
 * A 1080p bitmap is ~8 MB, so only the *selected* photo is decoded at full size.
 */
class WallpaperStore(context: Context) {

    private val dir = File(context.filesDir, "wallpapers")
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Newest first. */
    var photos by mutableStateOf<List<UserPhoto>>(emptyList())
        private set

    var selectedId by mutableStateOf(prefs.getString(KEY_SELECTED, null) ?: WallpaperStyle.Aurora.id)
        private set

    var backdrop by mutableStateOf<Backdrop>(
        Backdrop.Style(WallpaperStyle.fromId(selectedId) ?: WallpaperStyle.Aurora)
    )
        private set

    suspend fun load() {
        photos = withContext(Dispatchers.IO) {
            dir.listFiles { f -> f.extension == "jpg" }.orEmpty()
                .sortedByDescending { it.lastModified() }
                .mapNotNull { file -> decodeSampled(file, THUMB_W, THUMB_H)?.let { UserPhoto(file, it.asImageBitmap()) } }
        }
        select(selectedId)
    }

    suspend fun select(id: String) {
        val style = WallpaperStyle.fromId(id)
        val next = when {
            style != null -> Backdrop.Style(style)
            id.startsWith(PHOTO_PREFIX) -> withContext(Dispatchers.IO) {
                decodeSampled(File(dir, id.removePrefix(PHOTO_PREFIX)), SCREEN_W, SCREEN_H)
            }?.let { Backdrop.Photo(it.asImageBitmap()) }
            else -> null
        }
        // A missing or unreadable photo falls back to the default rather than a black screen.
        backdrop = next ?: Backdrop.Style(WallpaperStyle.Aurora)
        selectedId = if (next != null) id else WallpaperStyle.Aurora.id
        prefs.edit().putString(KEY_SELECTED, selectedId).apply()
    }

    /** Saves an uploaded/picked image and makes it the wallpaper. False if it isn't a decodable image. */
    suspend fun addPhoto(bytes: ByteArray): Boolean {
        val (photo, full) = withContext(Dispatchers.IO) { save(bytes) } ?: return false
        val all = listOf(photo) + photos
        photos = all.take(MAX_PHOTOS)
        withContext(Dispatchers.IO) { all.drop(MAX_PHOTOS).forEach { it.file.delete() } }
        backdrop = Backdrop.Photo(full.asImageBitmap())
        selectedId = photo.id
        prefs.edit().putString(KEY_SELECTED, selectedId).apply()
        return true
    }

    suspend fun remove(photo: UserPhoto) {
        withContext(Dispatchers.IO) { photo.file.delete() }
        photos = photos - photo
        if (selectedId == photo.id) select(WallpaperStyle.Aurora.id)
    }

    private fun save(bytes: ByteArray): Pair<UserPhoto, Bitmap>? {
        val bitmap = decodeForScreen(bytes) ?: return null
        dir.mkdirs()
        val file = File(dir, "${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val thumb = scaleToCover(bitmap, THUMB_W, THUMB_H)
        return UserPhoto(file, thumb.asImageBitmap()) to bitmap
    }

    /** Decode → apply camera rotation → shrink so it just covers a 1080p screen. */
    private fun decodeForScreen(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, SCREEN_W, SCREEN_H)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleToCover(rotateUpright(decoded, bytes), SCREEN_W, SCREEN_H)
    }

    private fun decodeSampled(file: File, minW: Int, minH: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, minW, minH)
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    /** Largest power of two that still leaves the image at least [minW]×[minH]. */
    private fun sampleSizeFor(w: Int, h: Int, minW: Int, minH: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= minW && h / (sample * 2) >= minH) sample *= 2
        // Extreme shapes (panoramas) never get small on both sides; cap the pixel count too.
        while (w.toLong() / sample * (h / sample) > MAX_DECODE_PIXELS) sample *= 2
        return sample
    }

    private fun scaleToCover(bitmap: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w / bitmap.width.toFloat(), h / bitmap.height.toFloat())
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
    }

    /** Phone cameras save sideways pixels plus an EXIF "rotate me" flag; bake the rotation in. */
    private fun rotateUpright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val KEY_SELECTED = "wallpaper"
        const val MAX_PHOTOS = 12
        const val SCREEN_W = 1920
        const val SCREEN_H = 1080
        const val THUMB_W = 384
        const val THUMB_H = 216
        /** ~4 screens of pixels (~33 MB as ARGB) — the most we'll ever decode at once. */
        const val MAX_DECODE_PIXELS = 4L * SCREEN_W * SCREEN_H
    }
}
