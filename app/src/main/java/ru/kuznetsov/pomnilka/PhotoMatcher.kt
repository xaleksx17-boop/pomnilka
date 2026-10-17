package ru.kuznetsov.pomnilka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class PhotoHit(val task: Task, val score: Float)

/**
 * Узнаёт объект справочника по снимку.
 * Основной режим — нейросеть MobileNet V3 (MediaPipe Image Embedder, ~4 МБ, офлайн):
 * снимок превращается в вектор признаков и сравнивается с фото из справочника.
 * Без модели — простой режим по цвету и форме (хуже, но работает для непохожих объектов).
 * Каждое подтверждённое распознавание добавляет снимок в эталоны — со временем узнаёт лучше.
 */
object PhotoMatcher {
    private const val TAG = "PhotoMatcher"
    const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
    private const val MAX_REFS = 6
    private val main = Handler(Looper.getMainLooper())

    private var embedder: ImageEmbedder? = null
    private val cache = HashMap<String, FloatArray>()   // путь+режим -> вектор

    private fun modelFile(ctx: Context) = File(ctx.filesDir, "embedder.tflite")
    fun hasModel(ctx: Context) = modelFile(ctx).let { it.exists() && it.length() > 100_000 }

    /** Порог «узнал уверенно» зависит от режима. */
    fun threshold(ctx: Context) = if (hasModel(ctx)) 0.55f else 0.80f

    fun downloadModel(ctx: Context, progress: (Int) -> Unit, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val err = try {
                Net.download(MODEL_URL, modelFile(app)) { p -> main.post { progress(p) } }
                reset(); null
            } catch (e: Exception) { e.message ?: e.toString() }
            main.post { done(err) }
        }.start()
    }

    fun importModel(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val err = try {
                app.contentResolver.openInputStream(uri)?.use { i -> modelFile(app).outputStream().use { i.copyTo(it) } }
                    ?: throw Exception("Не открыть файл")
                reset(); null
            } catch (e: Exception) { e.message ?: e.toString() }
            main.post { done(err) }
        }.start()
    }

    fun deleteModel(ctx: Context) { modelFile(ctx).delete(); reset() }

    @Synchronized private fun reset() {
        embedder?.close(); embedder = null; cache.clear()
    }

    @Synchronized private fun getEmbedder(ctx: Context): ImageEmbedder? {
        embedder?.let { return it }
        if (!hasModel(ctx)) return null
        return try {
            val bytes = modelFile(ctx).readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes); buf.rewind()
            val opts = ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
                .setRunningMode(RunningMode.IMAGE)
                .setL2Normalize(true)
                .setQuantize(false)
                .build()
            ImageEmbedder.createFromOptions(ctx, opts).also { embedder = it }
        } catch (e: Throwable) {
            Log.w(TAG, "embedder", e); null
        }
    }

    private fun centerSquare(b: Bitmap): Bitmap {
        val s = minOf(b.width, b.height)
        val sq = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        return Bitmap.createScaledBitmap(sq, 256, 256, true).copy(Bitmap.Config.ARGB_8888, false)
    }

    @Synchronized private fun feature(ctx: Context, bmp: Bitmap): FloatArray {
        val sq = centerSquare(bmp)
        getEmbedder(ctx)?.let { e ->
            try {
                val r = e.embed(BitmapImageBuilder(sq).build())
                return normalize(r.embeddingResult().embeddings()[0].floatEmbedding())
            } catch (t: Throwable) { Log.w(TAG, "embed", t) }
        }
        return simpleFeature(sq)
    }

    /** Простой дескриптор: цветовая гистограмма 4×4×4 + яркостная карта 8×8. */
    private fun simpleFeature(b: Bitmap): FloatArray {
        val s = Bitmap.createScaledBitmap(b, 32, 32, true)
        val hist = FloatArray(64)
        val gray = FloatArray(64)
        for (y in 0 until 32) for (x in 0 until 32) {
            val p = s.getPixel(x, y)
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val bl = p and 0xff
            hist[(r / 64) * 16 + (g / 64) * 4 + bl / 64] += 1f
            gray[(y / 4) * 8 + x / 4] += 0.299f * r + 0.587f * g + 0.114f * bl
        }
        val mean = gray.average().toFloat()
        for (i in gray.indices) gray[i] -= mean
        val h = normalize(hist); val gr = normalize(gray)
        return FloatArray(128) { if (it < 64) h[it] * 0.75f else gr[it - 64] * 0.66f }.let { normalize(it) }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun refsDir(ctx: Context, taskId: Long) = File(ctx.filesDir, "refs/$taskId")

    private fun references(ctx: Context, t: Task): List<String> =
        listOfNotNull(t.photoPath?.takeIf { File(it).exists() }) +
            (refsDir(ctx, t.id).listFiles()?.map { it.absolutePath } ?: emptyList())

    private fun refFeature(ctx: Context, path: String): FloatArray? {
        val key = "$path|${hasModel(ctx)}"
        cache[key]?.let { return it }
        val bmp = Photos.thumbnail(path, 512) ?: return null
        return feature(ctx, bmp).also { cache[key] = it }
    }

    /** Сравнить снимок со всем справочником. Вызывать в фоне. Результат отсортирован по убыванию сходства. */
    fun match(ctx: Context, shot: Bitmap): List<PhotoHit> {
        val f = feature(ctx, shot)
        return Db.get(ctx).allTasks().mapNotNull { t ->
            val best = references(ctx, t).mapNotNull { refFeature(ctx, it) }.maxOfOrNull { cosine(f, it) }
            best?.let { PhotoHit(t, it) }
        }.sortedByDescending { it.score }
    }

    /** Подтверждённый снимок — в эталоны задачи (храним последние MAX_REFS). */
    fun addReference(ctx: Context, taskId: Long, shotFile: File) {
        val dir = refsDir(ctx, taskId).apply { mkdirs() }
        val dst = File(dir, "ref_${System.currentTimeMillis()}.jpg")
        BitmapFactory.decodeFile(shotFile.absolutePath)?.let { bmp ->
            dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_REFS)?.forEach { it.delete() }
    }

    fun deleteReferences(ctx: Context, taskId: Long) { refsDir(ctx, taskId).deleteRecursively() }
}
