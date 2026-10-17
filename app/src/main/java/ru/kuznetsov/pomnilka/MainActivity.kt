package ru.kuznetsov.pomnilka

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_LISTEN = "ru.kuznetsov.pomnilka.LISTEN"
        const val ACTION_PHOTO = "ru.kuznetsov.pomnilka.PHOTO"
    }

    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var adapter: TaskAdapter
    private lateinit var status: TextView
    private lateinit var partial: TextView
    private lateinit var empty: TextView
    private lateinit var micFab: ExtendedFloatingActionButton
    private var lastResult: DoneResult? = null
    private var shotFile: File? = null

    // ---------- лаунчеры ----------

    private val googleSpeech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        setListening(false)
        val list = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (r.resultCode == Activity.RESULT_OK && !list.isNullOrEmpty()) handleAlternatives(list)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVosk() else show("Без доступа к микрофону офлайн-распознавание не работает.")
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val takeShot = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shotFile
        if (ok && f != null) recognizePhoto(f)
    }

    private val editTask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(TaskEditActivity.RESULT_MESSAGE)?.let { answer(it) }
        refresh()
    }

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        speaker = Speaker(this)
        Reminders.createChannel(this)

        status = findViewById(R.id.statusText)
        partial = findViewById(R.id.partialText)
        empty = findViewById(R.id.emptyView)
        micFab = findViewById(R.id.micFab)

        adapter = TaskAdapter(onClick = { openEditor(it.id) }, onDone = { confirmDone(it) })
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        micFab.setOnClickListener { if (VoskEngine.isListening()) { VoskEngine.stop(); setListening(false) } else startListening() }
        findViewById<FloatingActionButton>(R.id.photoFab).setOnClickListener { startPhoto() }

        val input = findViewById<EditText>(R.id.inputText)
        val send = {
            val txt = input.text.toString().trim()
            if (txt.isNotEmpty()) { input.setText(""); process(txt) }
        }
        findViewById<ImageButton>(R.id.sendBtn).setOnClickListener { send() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        Reminders.rescheduleAll(this)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            ACTION_LISTEN -> startListening()
            ACTION_PHOTO -> startPhoto()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        VoskEngine.stop()
        speaker.shutdown()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_agenda -> answer(Answers.agenda(Db.get(this).allTasks()))
            R.id.m_add -> openEditor(0)
            R.id.m_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refresh() {
        val tasks = Db.get(this).allTasks()
        adapter.submit(tasks)
        empty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun show(text: String) {
        status.text = text
        partial.visibility = View.GONE
    }

    /** Показать и (если включено) произнести ответ. */
    private fun answer(text: String) {
        show(text)
        if (prefs.speakReply) speaker.say(text)
    }

    private fun openEditor(id: Long, fill: Intent.() -> Unit = {}) {
        editTask.launch(Intent(this, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_ID, id).apply(fill))
    }

    // ---------- голос ----------

    private fun setListening(on: Boolean) {
        micFab.text = if (on) "Слушаю… (стоп)" else "Сказать"
        if (on) { partial.text = ""; partial.visibility = View.VISIBLE }
    }

    private fun startListening() {
        speaker.stop()
        val useVosk = when (prefs.engine) {
            "vosk" -> true
            "google" -> false
            else -> VoskEngine.isReady(this)
        }
        if (useVosk) {
            if (!VoskEngine.isReady(this)) {
                show("Офлайн-модель не скачана. Откройте Настройки → «Скачать модель Vosk».")
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else startVosk()
        } else startGoogle()
    }

    private fun startGoogle() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Например: «полил красулю»")
        try {
            setListening(true)
            googleSpeech.launch(i)
        } catch (e: ActivityNotFoundException) {
            setListening(false)
            if (VoskEngine.isReady(this)) startListeningVoskFallback()
            else show("На телефоне нет распознавания Google. Скачайте офлайн-модель в Настройках.")
        }
    }

    private fun startListeningVoskFallback() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        else startVosk()
    }

    private fun startVosk() {
        setListening(true)
        partial.text = "Загружаю модель…"
        VoskEngine.listen(this, object : VoskEngine.Callback {
            override fun onPartial(text: String) { partial.text = text }
            override fun onResult(text: String) { setListening(false); handleAlternatives(listOf(text)) }
            override fun onError(msg: String) { setListening(false); show(msg) }
        })
    }

    /** Из нескольких вариантов распознавания берём первый, который понятен. */
    private fun handleAlternatives(alts: List<String>) {
        val refs = Db.get(this).allTasks().map { it.toRef() }
        val good = alts.firstOrNull { a ->
            val c = CommandParser.parse(a)
            c.isUndo || c.isAgenda || CommandParser.match(c, refs).isNotEmpty()
        }
        process(good ?: alts.first())
    }

    // ---------- обработка команды ----------

    private fun process(text: String) {
        val db = Db.get(this)
        val tasks = db.allTasks()
        val cmd = CommandParser.parse(text)
        partial.visibility = View.VISIBLE
        partial.text = "«$text»"

        when {
            cmd.isUndo -> {
                val r = lastResult
                if (r == null) answer("Нечего отменять.")
                else { Actions.undo(this, r); lastResult = null; answer("Отменила: ${r.task.title.lowercase()}."); refresh() }
                return
            }
            cmd.isAgenda -> { answer(Answers.agenda(tasks)); return }
        }

        val matches = CommandParser.match(cmd, tasks.map { it.toRef() }).mapNotNull { m -> tasks.firstOrNull { it.id == m.id } }

        if (cmd.isQuery) {
            if (matches.isEmpty()) answer("Не нашла «${cmd.objectText}» в справочнике.")
            else answer(matches.joinToString("\n") { Answers.whenNext(it) })
            return
        }

        when {
            matches.size == 1 -> doDone(matches[0], cmd.dayOffset, text, cmd.interval)
            matches.size > 1 -> choose("Что именно?", matches) { doDone(it, cmd.dayOffset, text, cmd.interval) }
            cmd.objectWords.isEmpty() -> answer("Не поняла. Скажите, например: «полил красулю» или «когда кормить кота?»")
            else -> {
                show("«${cmd.suggestedTitle()}» нет в справочнике — создайте запись.")
                openEditor(0) {
                    putExtra(TaskEditActivity.EXTRA_TITLE, cmd.suggestedTitle())
                    putExtra(TaskEditActivity.EXTRA_KEYWORDS, cmd.objectText)
                    putExtra(TaskEditActivity.EXTRA_INTERVAL, cmd.interval?.days ?: 7)
                    putExtra(TaskEditActivity.EXTRA_MARK_DONE, cmd.verb != null)
                    putExtra(TaskEditActivity.EXTRA_DAY_OFFSET, cmd.dayOffset)
                    putExtra(TaskEditActivity.EXTRA_TEXT, text)
                }
            }
        }
    }

    private fun choose(title: String, tasks: List<Task>, pick: (Task) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(tasks.map { it.title }.toTypedArray()) { _, i -> pick(tasks[i]) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDone(t: Task) {
        AlertDialog.Builder(this)
            .setTitle(t.title)
            .setMessage("Отметить как сделанное сейчас?")
            .setPositiveButton("Сделано") { _, _ -> doDone(t, 0, "Отмечено вручную", null) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doDone(task: Task, dayOffset: Int, text: String, interval: Interval?) {
        val at = System.currentTimeMillis() + dayOffset * 86_400_000L
        val r = Actions.markDone(this, task, at, text, interval)
        lastResult = r
        answer(Answers.done(r))
        refresh()
        Snackbar.make(findViewById(R.id.root), r.task.title + " ✓", Snackbar.LENGTH_LONG)
            .setAnchorView(micFab)
            .setAction("Отменить") {
                Actions.undo(this, r); lastResult = null
                show("Отменено."); refresh()
            }.show()
    }

    // ---------- фото ----------

    private fun startPhoto() {
        speaker.stop()
        val dir = File(cacheDir, "shots").apply { mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        shotFile = f
        try {
            takeShot.launch(Photos.uriFor(this, f))
        } catch (e: ActivityNotFoundException) {
            show("Не найдено приложение камеры.")
        }
    }

    private fun recognizePhoto(f: File) {
        show("Смотрю на фото…")
        Thread {
            val path = Photos.normalizeCameraFile(f)
            val bmp = path?.let { BitmapFactory.decodeFile(it) }
            val hits = if (bmp != null) PhotoMatcher.match(this, bmp) else emptyList()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bmp == null) show("Не удалось прочитать снимок.") else showPhotoResult(File(path!!), hits)
            }
        }.start()
    }

    private fun photoView(f: File): View {
        val iv = ImageView(this)
        iv.adjustViewBounds = true
        iv.maxHeight = (resources.displayMetrics.density * 220).toInt()
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(Photos.thumbnail(f.absolutePath, 800))
        val pad = (resources.displayMetrics.density * 16).toInt()
        iv.setPadding(pad, pad, pad, 0)
        return iv
    }

    private fun showPhotoResult(shot: File, hits: List<PhotoHit>) {
        val all = Db.get(this).allTasks()
        if (all.none { it.photoPath != null }) {
            answer("В справочнике нет фото для сравнения. Откройте запись и добавьте фото.")
            offerPick(shot, all)
            return
        }
        val thr = PhotoMatcher.threshold(this)
        val best = hits.firstOrNull()
        if (best == null || best.score < thr) {
            answer("Не узнала. Выберите, что это.")
            offerPick(shot, all)
            return
        }
        // одно растение может быть в нескольких записях (полив, подкормка) — берём всех близких
        val group = hits.filter { it.score >= thr && it.score >= best.score - 0.03f }.map { it.task }
        val text = group.joinToString("\n") { Answers.whenNext(it) }
        answer(text)
        AlertDialog.Builder(this)
            .setTitle("Узнала (${(best.score * 100).toInt()}%)")
            .setView(photoView(shot))
            .setMessage(text)
            .setPositiveButton("Сделано") { _, _ ->
                val done: (Task) -> Unit = { t ->
                    PhotoMatcher.addReference(this, t.id, shot)
                    doDone(t, 0, "Отмечено по фото", null)
                }
                if (group.size == 1) done(group[0]) else choose("Что сделано?", group, done)
            }
            .setNeutralButton("Не то") { _, _ -> offerPick(shot, all) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Ручной выбор: учит распознавание этим снимком или создаёт новую запись с этим фото. */
    private fun offerPick(shot: File, all: List<Task>) {
        val names = all.map { it.title } + "➕ Новая запись с этим фото"
        AlertDialog.Builder(this)
            .setTitle("Что на фото?")
            .setView(photoView(shot))
            .setItems(names.toTypedArray()) { _, i ->
                if (i == all.size) {
                    openEditor(0) { putExtra(TaskEditActivity.EXTRA_PHOTO, shot.absolutePath) }
                } else {
                    val t = all[i]
                    PhotoMatcher.addReference(this, t.id, shot)
                    answer(Answers.whenNext(t))
                    AlertDialog.Builder(this)
                        .setTitle(t.title)
                        .setMessage(Answers.whenNext(t) + "\n\nСнимок запомнила — в следующий раз узнаю.")
                        .setPositiveButton("Сделано сейчас") { _, _ -> doDone(t, 0, "Отмечено по фото", null) }
                        .setNegativeButton("Закрыть", null)
                        .show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
