package dev.momolite

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** Chat bubble styling shared by the activity and the layout-render tests. */
object Bubbles {
    private const val MINE = "#1F4E79"
    private const val SYSTEM = "#FFF3CD"
    private const val OTHER = "#E4E8EE"

    fun apply(bubble: TextView, msg: Msg) {
        bubble.text = msg.text
        val mine = msg.role == "user"
        bubble.setTextColor(if (mine) Color.WHITE else Color.parseColor("#222222"))
        bubble.background = GradientDrawable().apply {
            cornerRadius = 36f
            setColor(Color.parseColor(if (mine) MINE else if (msg.role == "system") SYSTEM else OTHER))
        }
        (bubble.layoutParams as FrameLayout.LayoutParams).gravity = if (mine) Gravity.START else Gravity.END
    }
}
