package ru.kuznetsov.pomnilka

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Офлайн-распознавание русской речи (Vosk). Модель ~45 МБ скачивается один раз. */
object VoskEngine {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var model: Model? = null
    private var service: SpeechService? = null

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk-model")
    fun isReady(ctx: Context) = File(modelDir(ctx), ".ready").exists()

    fun deleteModel(ctx: Context) {
        stop()
        model?.close(); model = null
        modelDir(ctx).deleteRecursively()
    }

    /** Скачать модель. progress(0..100), done(ошибка или null) вызываются в главном потоке. */
    fun download(ctx: Context, progress: (Int) -> Unit, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var err: String? = null
            try {
                val conn = Net.open(MODEL_URL)
                val total = conn.contentLengthLong
                val counting = object : java.io.FilterInputStream(conn.inputStream) {
                    var count = 0L; var last = -1
                    private fun tick(n: Int) {
                        if (n > 0 && total > 0) {
                            count += n
                            val p = (count * 100 / total).toInt()
                            if (p != last) { last = p; main.post { progress(p) } }
                        }
                    }
                    override fun read(): Int = super.read().also { if (it >= 0) tick(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { tick(it) }
                }
                counting.use { unzipModel(app, it) }
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            main.post { done(err) }
        }.start()
    }

    /** Установить модель из zip-файла, выбранного пользователем (если скачать с телефона не получается). */
    fun importZip(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val err = try {
                app.contentResolver.openInputStream(uri)?.use { unzipModel(app, it) } ?: throw Exception("Не открыть файл")
                null
            } catch (e: Exception) { e.message ?: e.toString() }
            main.post { done(err) }
        }.start()
    }

    private fun unzipModel(ctx: Context, input: InputStream) {
        val target = modelDir(ctx)
        val tmp = File(ctx.filesDir, "vosk-model.tmp").apply { deleteRecursively(); mkdirs() }
        val tmpCanon = tmp.canonicalPath
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // отбрасываем корневую папку архива (vosk-model-small-ru-0.22/…)
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(tmp, rel)
                if (!out.canonicalPath.startsWith(tmpCanon)) continue
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        if (!File(tmp, "am").exists() && !File(tmp, "conf").exists()) {
            tmp.deleteRecursively()
            throw Exception("В архиве нет модели Vosk")
        }
        model?.close(); model = null
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw Exception("Не удалось сохранить модель")
        File(target, ".ready").writeText("ok")
    }

    interface Callback {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(msg: String)
    }

    /** Запуск прослушивания. Требуется разрешение RECORD_AUDIO. */
    fun listen(ctx: Context, cb: Callback) {
        val app = ctx.applicationContext
        stop()
        Thread {
            try {
                val m = model ?: Model(modelDir(app).absolutePath).also { model = it }
                val rec = Recognizer(m, 16000f)
                main.post {
                    try {
                        val s = SpeechService(rec, 16000f)
                        service = s
                        var finished = false
                        fun finish(text: String?, err: String?) {
                            if (finished) return
                            finished = true
                            stop()
                            if (!text.isNullOrBlank()) cb.onResult(text) else cb.onError(err ?: "Ничего не расслышала")
                        }
                        s.startListening(object : RecognitionListener {
                            override fun onPartialResult(hypothesis: String?) {
                                val t = field(hypothesis, "partial")
                                if (t.isNotBlank() && !finished) cb.onPartial(t)
                            }
                            override fun onResult(hypothesis: String?) {
                                val t = field(hypothesis, "text")
                                if (t.isNotBlank()) finish(t, null)
                            }
                            override fun onFinalResult(hypothesis: String?) = finish(field(hypothesis, "text"), null)
                            override fun onError(exception: Exception?) = finish(null, exception?.message ?: "Ошибка распознавания")
                            override fun onTimeout() = finish(null, null)
                        }, 10000)
                    } catch (e: Exception) {
                        cb.onError(e.message ?: "Ошибка микрофона")
                    }
                }
            } catch (e: Exception) {
                main.post { cb.onError("Модель не загрузилась: ${e.message}") }
            }
        }.start()
    }

    fun isListening() = service != null

    fun stop() {
        service?.let {
            try { it.cancel(); it.shutdown() } catch (_: Exception) {}
        }
        service = null
    }

    private fun field(json: String?, name: String): String =
        try { JSONObject(json ?: "{}").optString(name, "") } catch (e: Exception) { "" }
}
