package ru.kuznetsov.pomnilka

import android.Manifest
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var swCal: MaterialSwitch
    private lateinit var spCal: Spinner
    private var calendars: List<CalendarInfo> = emptyList()

    private val calPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) { loadCalendars(); applyCalendar() }
        else { swCal.isChecked = false; toast("Без доступа к календарю дублирование невозможно") }
    }

    private val voskZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy(R.id.pbVosk, true, indeterminate = true)
            VoskEngine.importZip(this, uri) { err -> busy(R.id.pbVosk, false); report(err); refreshModels() }
        }
    }

    private val embFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PhotoMatcher.importModel(this, uri) { err -> report(err); refreshModels() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Настройки"
        prefs = Prefs(this)

        // распознавание речи
        val rg = findViewById<RadioGroup>(R.id.rgEngine)
        rg.check(when (prefs.engine) { "google" -> R.id.rbGoogle; "vosk" -> R.id.rbVosk; else -> R.id.rbAuto })
        rg.setOnCheckedChangeListener { _, id ->
            prefs.engine = when (id) { R.id.rbGoogle -> "google"; R.id.rbVosk -> "vosk"; else -> "auto" }
        }
        findViewById<Button>(R.id.btnVoskDl).setOnClickListener {
            busy(R.id.pbVosk, true)
            VoskEngine.download(this, { p -> findViewById<ProgressBar>(R.id.pbVosk).progress = p }) { err ->
                busy(R.id.pbVosk, false); report(err); refreshModels()
            }
        }
        findViewById<Button>(R.id.btnVoskFile).setOnClickListener { voskZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        findViewById<Button>(R.id.btnVoskDel).setOnClickListener { VoskEngine.deleteModel(this); refreshModels() }

        // распознавание по фото
        findViewById<Button>(R.id.btnEmbDl).setOnClickListener {
            busy(R.id.pbEmb, true)
            PhotoMatcher.downloadModel(this, { p -> findViewById<ProgressBar>(R.id.pbEmb).progress = p }) { err ->
                busy(R.id.pbEmb, false); report(err); refreshModels()
            }
        }
        findViewById<Button>(R.id.btnEmbFile).setOnClickListener { embFile.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnEmbDel).setOnClickListener { PhotoMatcher.deleteModel(this); refreshModels() }

        // время напоминания
        val btnTime = findViewById<Button>(R.id.btnTime)
        fun showTime() { btnTime.text = "Напоминать в %02d:%02d".format(prefs.remindHour, prefs.remindMinute) }
        showTime()
        btnTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                prefs.remindHour = h; prefs.remindMinute = m; showTime()
                Thread { Reminders.rescheduleAll(this); if (prefs.calendarEnabled) CalendarSync.resyncAll(this) }.start()
            }, prefs.remindHour, prefs.remindMinute, true).show()
        }

        val swSpeak = findViewById<MaterialSwitch>(R.id.swSpeak)
        swSpeak.isChecked = prefs.speakReply
        swSpeak.setOnCheckedChangeListener { _, c -> prefs.speakReply = c }

        // календарь
        swCal = findViewById(R.id.swCal)
        spCal = findViewById(R.id.spCal)
        swCal.isChecked = prefs.calendarEnabled && CalendarSync.hasPermission(this)
        swCal.setOnCheckedChangeListener { _, c ->
            if (c && !CalendarSync.hasPermission(this)) {
                calPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            } else applyCalendar()
        }
        findViewById<Button>(R.id.btnLocalCal).setOnClickListener {
            if (!CalendarSync.hasPermission(this)) {
                swCal.isChecked = true // запросит разрешение
                return@setOnClickListener
            }
            val id = CalendarSync.createLocalCalendar(this)
            if (id == null) toast("Не удалось создать календарь")
            else { prefs.calendarId = id; loadCalendars(); applyCalendar(); toast("Календарь «Помнилка» создан") }
        }
        val swDone = findViewById<MaterialSwitch>(R.id.swCalDone)
        swDone.isChecked = prefs.calendarLogDone
        swDone.setOnCheckedChangeListener { _, c -> prefs.calendarLogDone = c }
        val swAlert = findViewById<MaterialSwitch>(R.id.swCalAlert)
        swAlert.isChecked = prefs.calendarAlert
        swAlert.setOnCheckedChangeListener { _, c -> prefs.calendarAlert = c; resyncCalendar() }

        spCal.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val cal = calendars.getOrNull(pos) ?: return
                if (cal.id != prefs.calendarId) { prefs.calendarId = cal.id; resyncCalendar() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        loadCalendars()
        refreshModels()
    }

    private fun loadCalendars() {
        calendars = CalendarSync.listCalendars(this)
        spCal.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, calendars)
        val idx = calendars.indexOfFirst { it.id == prefs.calendarId }
        if (idx >= 0) spCal.setSelection(idx)
        else if (calendars.isNotEmpty()) prefs.calendarId = calendars[0].id
        spCal.visibility = if (calendars.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun applyCalendar() {
        prefs.calendarEnabled = swCal.isChecked && CalendarSync.hasPermission(this)
        if (prefs.calendarEnabled && calendars.isEmpty())
            toast("На телефоне нет доступных календарей — нажмите «Создать календарь «Помнилка»»")
        resyncCalendar()
    }

    private fun resyncCalendar() {
        Thread { CalendarSync.resyncAll(this) }.start()
    }

    private fun refreshModels() {
        findViewById<TextView>(R.id.tvVosk).text =
            if (VoskEngine.isReady(this)) "Офлайн-модель Vosk установлена ✓" else "Офлайн-модель не установлена"
        findViewById<TextView>(R.id.tvEmb).text =
            if (PhotoMatcher.hasModel(this)) "Нейросеть MobileNet установлена ✓ — узнаёт уверенно"
            else "Нейросеть не скачана — работает простой режим (по цвету и форме). Скачайте для точности."
    }

    private fun busy(pbId: Int, on: Boolean, indeterminate: Boolean = false) {
        findViewById<ProgressBar>(pbId).apply {
            visibility = if (on) View.VISIBLE else View.GONE
            isIndeterminate = indeterminate
            progress = 0
        }
    }

    private fun report(err: String?) = toast(if (err == null) "Готово" else "Ошибка: $err")
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
