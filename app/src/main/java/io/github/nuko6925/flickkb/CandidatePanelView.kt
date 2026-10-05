package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.ceil

/** 展開した候補一覧。キーボード部(候補バー込み)と同じ大きさで重ねる */
@SuppressLint("ViewConstructor")
class CandidatePanelView(
    context: Context,
    private val onPick: (Int) -> Unit,
    private val onCollapse: () -> Unit,
) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private val rowH = 46 * dp
    private val gutter = 56 * dp

    private val grid = Grid(context)
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val collapse = ChevronView(context, up = true).apply { setOnClickListener { onCollapse() } }

    var theme = KbTheme(false)
        set(v) {
            field = v
            collapse.color = v.text
            grid.invalidate()
        }

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(collapse, LayoutParams(gutter.toInt(), rowH.toInt(), Gravity.TOP or Gravity.END))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 親の仮計測では高さ 0 (EmojiPanelView と同じ理由)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) heightMeasureSpec
        else MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, h)
    }

    fun set(cands: List<Candidate>, selected: Int) {
        grid.items = cands.map { it.text }
        grid.selected = selected
        grid.requestLayout()
        grid.invalidate()
        scroll.scrollTo(0, 0)
    }

    private inner class Grid(context: Context) : View(context) {
        var items: List<String> = emptyList()
        var selected = -1
        private val rects = ArrayList<RectF>()
        private var rows = 0
        private var avail = 0f
        private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 18 * dp * resources.configuration.fontScale
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var x0 = 0f
        private var y0 = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            layoutCells(w)
            setMeasuredDimension(w, (rows * rowH).toInt())
        }

        /** 等幅グリッドに詰め、長い候補は複数セルにまたがる */
        private fun layoutCells(w: Int) {
            rects.clear()
            avail = w - gutter
            val cols = (avail / (56 * dp)).toInt().coerceIn(1, 6)
            val cellW = avail / cols
            var col = 0
            var row = 0
            for (t in items) {
                val span = ceil((textP.measureText(t) + 20 * dp) / cellW).toInt().coerceIn(1, cols)
                if (col + span > cols) { row++; col = 0 }
                rects.add(RectF(col * cellW, row * rowH, (col + span) * cellW, (row + 1) * rowH))
                col += span
            }
            rows = if (items.isEmpty()) 0 else row + 1
        }

        override fun onDraw(c: Canvas) {
            fill.color = theme.separator
            for (r in 1..rows) c.drawRect(0f, r * rowH - 0.5f * dp, avail, r * rowH + 0.5f * dp, fill)
            val fm = textP.fontMetrics
            items.forEachIndexed { i, t ->
                val r = rects.getOrNull(i) ?: return@forEachIndexed
                if (i == selected) {
                    fill.color = theme.candSelected
                    c.drawRoundRect(r.left + 3 * dp, r.top + 5 * dp, r.right - 3 * dp, r.bottom - 5 * dp, 8 * dp, 8 * dp, fill)
                }
                textP.color = theme.text
                c.drawText(t, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, textP)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.x - x0) < slop && abs(e.y - y0) < slop) {
                        val i = rects.indexOfFirst { it.contains(e.x, e.y) }
                        if (i >= 0) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onPick(i)
                        }
                    }
                }
            }
            return true
        }
    }
}
