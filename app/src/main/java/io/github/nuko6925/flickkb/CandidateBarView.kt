package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** ∨ / ∧ ボタン */
class ChevronView(context: Context, private val up: Boolean) : View(context) {
    var color = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 2 * dp
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val w = 8 * dp
        val h = 4.5f * dp * if (up) -1 else 1
        path.reset()
        path.moveTo(cx - w, cy - h); path.lineTo(cx, cy + h); path.lineTo(cx + w, cy - h)
        p.color = color
        c.drawPath(path, p)
    }
}

/** 1行の候補バー。溢れたら右端に展開ボタン */
@SuppressLint("ViewConstructor")
class CandidateBarView(
    context: Context,
    private val onPick: (Int) -> Unit,
    private val onExpand: () -> Unit,
) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }
    private val row = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val divider = View(context)
    private val expand = ChevronView(context, up = false).apply { setOnClickListener { onExpand() } }
    private val expandW = (48 * dp).toInt()

    var theme = KbTheme(false)
        set(v) {
            field = v
            divider.setBackgroundColor(v.separator)
            expand.color = v.text
        }

    init {
        orientation = HORIZONTAL
        scroll.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(divider, LayoutParams((1 * dp).toInt().coerceAtLeast(1), LayoutParams.MATCH_PARENT).apply {
            setMargins(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        })
        addView(expand, LayoutParams(expandW, LayoutParams.MATCH_PARENT))
        divider.visibility = GONE
        expand.visibility = GONE
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { updateExpand() } }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { updateExpand() } }
    }

    private fun updateExpand() {
        val show = row.childCount > 0 && row.width > width - expandW - divider.width
        val v = if (show) VISIBLE else GONE
        if (expand.visibility != v) {
            expand.visibility = v
            divider.visibility = v
        }
    }

    fun set(cands: List<Candidate>, selected: Int) {
        row.removeAllViews()
        cands.take(80).forEachIndexed { i, c ->
            val tv = TextView(context).apply {
                text = c.text
                textSize = 18f
                setTextColor(theme.text)
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                if (i == selected) background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(theme.candSelected)
                }
                setOnClickListener { onPick(i) }
            }
            row.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }
        if (selected >= 0) post {
            row.getChildAt(selected)?.let { v ->
                if (v.left < scroll.scrollX || v.right > scroll.scrollX + scroll.width)
                    scroll.smoothScrollTo(v.left - (8 * dp).toInt(), 0)
            }
        } else scroll.scrollTo(0, 0)
        if (cands.isEmpty()) updateExpand()
    }
}
