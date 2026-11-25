package dev.momolite

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/** Chat bubble styling, shared by the adapter and any layout checks. */
object Bubbles {
    fun apply(bubble: TextView, msg: Msg) {
        bubble.text = msg.text
        val (bg, fg) = when (msg.role) {
            "user" -> R.drawable.bubble_user to com.google.android.material.R.attr.colorOnPrimary
            "system" -> R.drawable.bubble_system to com.google.android.material.R.attr.colorOnTertiaryContainer
            else -> R.drawable.bubble_assistant to com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        bubble.setBackgroundResource(bg)
        bubble.setTextColor(MaterialColors.getColor(bubble, fg))
        // In RTL, START is the right edge: the user's own messages sit on the right.
        (bubble.layoutParams as FrameLayout.LayoutParams).gravity = if (msg.role == "user") Gravity.START else Gravity.END
    }
}

class MessageAdapter(private val items: List<Msg>) : RecyclerView.Adapter<MessageAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) { val bubble: TextView = v.findViewById(R.id.bubble) }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_message, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) = Bubbles.apply(h.bubble, items[i])
}

/** One row in the history / reminders lists. */
data class Row(val title: String, val sub: String)

class RowAdapter(
    private val rows: () -> List<Row>,
    private val onOpen: ((Int) -> Unit)?,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.row_title)
        val sub: TextView = v.findViewById(R.id.row_sub)
        val del: View = v.findViewById(R.id.row_delete)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
    override fun getItemCount() = rows().size
    override fun onBindViewHolder(h: VH, i: Int) {
        val r = rows()[i]
        h.title.text = r.title; h.sub.text = r.sub
        h.del.setOnClickListener { onDelete(h.bindingAdapterPosition) }
        h.itemView.setOnClickListener { onOpen?.invoke(h.bindingAdapterPosition) }
        h.itemView.isClickable = onOpen != null
    }
}
