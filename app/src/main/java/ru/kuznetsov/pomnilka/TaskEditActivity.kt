package ru.kuznetsov.pomnilka

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.time.LocalDate

class TaskEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KEYWORDS = "keywords"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_MARK_DONE = "mark_done"
        const val EXTRA_DAY_OFFSET = "day_offset"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PHOTO = "photo"
        const val RESULT_MESSAGE = "message"
    }

    private var original: Task? = null
    private var photoPath: String? = null
    private var nextDue: LocalDate? = null
    private var nextDueManual = false
    private var saved = false
    private var pendingCamera: File? = null

    private lateinit var photo: ImageView
    private lateinit var titleEdit: TextInputEditText
    private lateinit var keywords: TextInputEditText
    private lateinit var interval: TextInputEditText
    private lateinit var btnNext: Button
    private lateinit var markDone: CheckBox

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCamera ?: return@registerForActivityResult
        if (ok) Thread {
            val p = Photos.normalizeCameraFile(f)
            runOnUiThread { if (p != null) setPhoto(p) }
        }.start() else f.delete()
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) Thread {
            val p = Photos.importFromUri(this, uri)
            runOnUiThread { if (p != null) setPhoto(p) else Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_edit)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        photo = findViewById(R.id.photo)
        titleEdit = findViewById(R.id.title)
        keywords = findViewById(R.id.keywords)
        interval = findViewById(R.id.interval)
        btnNext = findViewById(R.id.btnNext)
        markDone = findViewById(R.id.markDone)

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val t = if (id > 0) Db.get(this).task(id) else null
        original = t
        titleEdit.setText(t?.title ?: intent.getStringExtra(EXTRA_TITLE) ?: "")
        keywords.setText(t?.keywords ?: intent.getStringExtra(EXTRA_KEYWORDS) ?: "")
        interval.setText((t?.intervalDays ?: intent.getIntExtra(EXTRA_INTERVAL, 7)).toString())
        nextDue = t?.nextDueDate
        photoPath = t?.photoPath
        setTitle(if (t == null) "Новая запись" else "Запись")

        // снимок, пришедший с экрана распознавания («новая запись с этим фото»)
        intent.getStringExtra(EXTRA_PHOTO)?.takeIf { t == null && savedInstanceState == null }?.let { src ->
            val dst = Photos.newFile(this)
            File(src).copyTo(dst, overwrite = true)
            photoPath = dst.absolutePath
        }

        markDone.isChecked = t == null && intent.getBooleanExtra(EXTRA_MARK_DONE, false)
        markDone.visibility = if (t == null) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.lastText).text =
            t?.lastDone?.let { "Последний раз: ${Fmt.dateTime(it)} (${Fmt.ago(it)})" } ?: "Ещё не выполнялось"

        val hist = t?.let { Db.get(this).history(it.id) } ?: emptyList()
        findViewById<TextView>(R.id.history).text =
            if (hist.isEmpty()) "Пока пусто" else hist.joinToString("\n") { "${Fmt.dateTime(it.doneAt)} — ${it.text}" }
        if (t == null) {
            findViewById<View>(R.id.historyHeader).visibility = View.GONE
            findViewById<View>(R.id.history).visibility = View.GONE
        }

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            val f = Photos.newFile(this)
            pendingCamera = f
            try { camera.launch(Photos.uriFor(this, f)) } catch (e: Exception) {
                Toast.makeText(this, "Нет приложения камеры", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btnNoPhoto).setOnClickListener { setPhoto(null) }
        btnNext.setOnClickListener { pickDate() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        refreshPhoto(); refreshNext()
    }

    private fun setPhoto(p: String?) {
        // удаляем промежуточный файл, если это не исходное фото записи
        if (photoPath != null && photoPath != original?.photoPath && photoPath != p) Photos.delete(photoPath)
        photoPath = p
        refreshPhoto()
    }

    private fun refreshPhoto() {
        val bmp = Photos.thumbnail(photoPath, 900)
        if (bmp != null) { photo.setImageBitmap(bmp); photo.alpha = 1f }
        else { photo.setImageResource(R.drawable.ic_image); photo.alpha = 0.3f }
    }

    private fun refreshNext() {
        btnNext.text = "Следующий раз: " + (nextDue?.let { "${Fmt.date(it)} (${Fmt.relative(it)})" } ?: "по интервалу после первой отметки")
    }

    private fun pickDate() {
        val d = nextDue ?: LocalDate.now().plusDays((interval.text.toString().toIntOrNull() ?: 7).toLong())
        DatePickerDialog(this, { _, y, m, day ->
            nextDue = LocalDate.of(y, m + 1, day); nextDueManual = true; refreshNext()
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun save() {
        val name = titleEdit.text.toString().trim()
        val days = interval.text.toString().toIntOrNull()
        if (name.isEmpty()) { titleEdit.error = "Введите название"; return }
        if (days == null || days < 1) { interval.error = "Не меньше 1 дня"; return }

        val o = original
        var due = nextDue
        // интервал поменяли, дату руками не трогали — пересчитать от последнего раза
        if (o != null && !nextDueManual && days != o.intervalDays && o.lastDone != null)
            due = Fmt.toLocalDate(o.lastDone).plusDays(days.toLong())
        if (o == null && due == null && !markDone.isChecked) due = LocalDate.now().plusDays(days.toLong())

        val base = (o ?: Task(title = name)).copy(
            title = name,
            keywords = keywords.text.toString().trim(),
            intervalDays = days,
            nextDue = due?.toEpochDay(),
            photoPath = photoPath
        )
        if (o?.photoPath != null && o.photoPath != photoPath) Photos.delete(o.photoPath)
        val t = Actions.save(this, base)
        saved = true

        val result = Intent()
        if (o == null && markDone.isChecked) {
            val offset = intent.getIntExtra(EXTRA_DAY_OFFSET, 0)
            val text = intent.getStringExtra(EXTRA_TEXT) ?: "Отмечено при создании"
            val r = Actions.markDone(this, t, System.currentTimeMillis() + offset * 86_400_000L, text, null)
            result.putExtra(RESULT_MESSAGE, "Новая запись. " + Answers.done(r))
        } else if (o == null) {
            result.putExtra(RESULT_MESSAGE, "Добавила: ${t.title}. " + Answers.whenNext(t))
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        if (!saved && isFinishing && photoPath != original?.photoPath) Photos.delete(photoPath)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (original != null) menuInflater.inflate(R.menu.edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.m_delete -> {
                val t = original ?: return true
                AlertDialog.Builder(this)
                    .setTitle("Удалить «${t.title}»?")
                    .setMessage("История, фото и событие в календаре тоже будут удалены.")
                    .setPositiveButton("Удалить") { _, _ ->
                        Actions.delete(this, t); saved = true; finish()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
