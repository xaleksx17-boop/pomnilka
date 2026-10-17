package ru.kuznetsov.pomnilka

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Net {
    /** Открыть соединение, пройдя редиректы. */
    fun open(urlStr: String): HttpURLConnection {
        var url = URL(urlStr)
        repeat(6) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect()
            } else if (code == 200) {
                return conn
            } else {
                conn.disconnect(); throw Exception("HTTP $code")
            }
        }
        throw Exception("Слишком много перенаправлений")
    }

    /** Скачивание файла с редиректами и прогрессом (0..100). Вызывать не из главного потока. */
    fun download(urlStr: String, out: File, progress: (Int) -> Unit) {
        val conn = open(urlStr)
        val total = conn.contentLengthLong
        val tmp = File(out.parentFile, out.name + ".part")
        conn.inputStream.use { inp ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var last = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val p = (read * 100 / total).toInt()
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
        }
        out.delete()
        if (!tmp.renameTo(out)) throw Exception("Не удалось сохранить файл")
    }
}
