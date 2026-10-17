package ru.kuznetsov.pomnilka

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate

class TaskAdapter(
    private val onClick: (Task) -> Unit,
    private val onDone: (Task) -> Unit
) : RecyclerView.Adapter<TaskAdapter.VH>() {

    private var items: List<Task> = emptyList()

    fun submit(list: List<Task>) {
        items = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val photo: ImageView = v.findViewById(R.id.photo)
        val title: TextView = v.findViewById(R.id.title)
        val next: TextView = v.findViewById(R.id.next)
        val last: TextView = v.findViewById(R.id.last)
        val done: ImageButton = v.findViewById(R.id.doneBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = items[pos]
        val ctx = h.itemView.context
        h.title.text = t.title
        val bmp = Photos.thumbnail(t.photoPath, 160)
        if (bmp != null) {
            h.photo.setImageBitmap(bmp); h.photo.alpha = 1f
        } else {
            h.photo.setImageResource(R.drawable.ic_image); h.photo.alpha = 0.3f
        }
        val due = t.nextDueDate
        val today = LocalDate.now()
        h.next.text = if (due == null) "ещё не отмечено · каждые ${Fmt.days(t.intervalDays)}"
        else "${Fmt.relative(due)} · ${Fmt.date(due)}"
        val color = when {
            due == null -> null
            due.isBefore(today) -> R.color.overdue
            due == today -> R.color.today
            else -> null
        }
        if (color != null) h.next.setTextColor(ContextCompat.getColor(ctx, color))
        else h.next.setTextColor(h.last.textColors.defaultColor)
        h.last.text = t.lastDone?.let { "последний раз ${Fmt.ago(it)} · каждые ${Fmt.days(t.intervalDays)}" } ?: ""
        h.itemView.setOnClickListener { onClick(t) }
        h.done.setOnClickListener { onDone(t) }
    }
}
