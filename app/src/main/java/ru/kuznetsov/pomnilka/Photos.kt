package ru.kuznetsov.pomnilka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/** Фото объектов справочника. Храним уменьшенную копию (до 1600 px) во внутренней памяти приложения. */
object Photos {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun newFile(ctx: Context) = File(dir(ctx), "photo_${System.currentTimeMillis()}.jpg")

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /** Копия из галереи -> уменьшенный JPEG у нас. */
    fun importFromUri(ctx: Context, uri: Uri): String? = try {
        val tmp = File(ctx.cacheDir, "import.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        val out = newFile(ctx)
        if (shrink(tmp, out)) out.absolutePath else null
    } catch (e: Exception) { null }

    /** Снимок с камеры уже лежит в нашем файле — пережимаем на месте. */
    fun normalizeCameraFile(f: File): String? {
        if (!f.exists() || f.length() == 0L) { f.delete(); return null }
        val tmp = File(f.parentFile, f.name + ".tmp")
        f.renameTo(tmp)
        return if (shrink(tmp, f)) f.absolutePath else null
    }

    private fun shrink(src: File, dst: File, max: Int = 1600): Boolean {
        try {
            val bmp = decode(src.absolutePath, max) ?: return false
            FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            return true
        } finally {
            if (src != dst) src.delete()
        }
    }

    private fun decode(path: String, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= max || o.outHeight / (sample * 2) >= max) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        if (rot == 0f && scale >= 1f) return bmp
        val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun thumbnail(path: String?, max: Int): Bitmap? {
        if (path == null || !File(path).exists()) return null
        val key = "$path@$max"
        cache.get(key)?.let { return it }
        return decode(path, max)?.also { cache.put(key, it) }
    }

    fun delete(path: String?) {
        if (path == null) return
        cache.evictAll()
        File(path).delete()
    }
}
