package ru.kuznetsov.pomnilka

import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

/** Справка и «О приложении». */
class HelpActivity : AppCompatActivity() {

    companion object {
        const val AUTHOR = "Кузнецов Александр Алексеевич"
        const val DEDICATION = "Создано для любимого котёнка"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Справка"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // посвящение — первым, на карточке
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            setCardBackgroundColor(0xFFE8F5E9.toInt())
            cardElevation = 0f
        }
        card.addView(TextView(this).apply {
            text = "🐾 $DEDICATION ❤️"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF1B5E20.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(20))
        })
        col.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        col.addView(TextView(this).apply {
            text = Html.fromHtml(HELP_HTML, Html.FROM_HTML_MODE_COMPACT)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(16), 0, 0)
        })

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
        col.addView(TextView(this).apply {
            text = Html.fromHtml(
                "<br><h3>О приложении</h3>" +
                    "<b>Помнилка</b>, версия $version<br>" +
                    "Автор: <b>$AUTHOR</b><br>" +
                    "<i>$DEDICATION</i> 🐱",
                Html.FROM_HTML_MODE_COMPACT
            )
            textSize = 15f
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

private const val HELP_HTML = """
<h3>Как пользоваться</h3>
Нажмите <b>«Сказать»</b> и произнесите, что сделали. Помнилка запишет это,
поставит следующий раз и напомнит.

<h3>Голосовые команды</h3>
• <b>«полил красулю»</b> — отметка, следующий раз по интервалу из справочника<br>
• <b>«вчера полила фикус»</b> — отметка вчерашним днём<br>
• <b>«полил красулю, следующий через 10 дней»</b> — сдвинуть только ближайший раз<br>
• <b>«подкормил фикус каждые две недели»</b> или <b>«раз в месяц»</b> — поменять интервал насовсем<br>
• <b>«когда поливать красулю?»</b> — ответит голосом<br>
• <b>«что сегодня?»</b> — что пора сделать<br>
• <b>«отмена»</b> — отменить последнюю отметку<br>
Если сказать о том, чего нет в справочнике, Помнилка предложит создать новую запись.

<h3>Справочник</h3>
Нажмите на запись, чтобы изменить название, интервал, дату следующего раза или фото.
В поле <b>«Другие слова»</b> добавьте другие названия и глаголы — например
«толстянка, денежное дерево, поливал», — чтобы голос узнавался увереннее.
Кнопка ✓ в списке — быстрая отметка без голоса.

<h3>Распознавание по фото</h3>
Добавьте фото к записям. Кнопка с камерой на главном экране: наведите на растение —
Помнилка узнает его и скажет, когда поливать. Каждое подтверждение запоминается,
и со временем она узнаёт увереннее. Если узнала не то — «Не то» и выберите вручную.
Для точности скачайте нейросеть в Настройках.

<h3>Напоминания</h3>
В назначенный день придёт уведомление с кнопками <b>«Сделано»</b> и <b>«Завтра»</b>.
Пока не отметите — напоминание повторяется каждый день. Время задаётся в Настройках.

<h3>Календарь</h3>
В Настройках включите «Дублировать в календарь» и выберите календарь
(или создайте «Помнилка» на телефоне). Туда попадут следующие сроки и отметки ✓.

<h3>Без интернета</h3>
Скачайте в Настройках офлайн-модель речи (Vosk, ~45 МБ) — тогда голос работает без сети.

<h3>Быстрый запуск</h3>
Долгое нажатие на значок приложения — ярлыки <b>«Сказать»</b> и <b>«Узнать по фото»</b>;
их можно вынести на рабочий стол.
"""
