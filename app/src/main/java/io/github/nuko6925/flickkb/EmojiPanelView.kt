package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.floor

/** 絵文字一覧 (assets/emoji.tsv)。端末フォントにない絵文字は除外 */
object EmojiData {
    val CATEGORIES = listOf(
        "smileys" to "😀", "animals" to "🐻", "food" to "🍎", "activity" to "⚽",
        "travel" to "🚗", "objects" to "💡", "symbols" to "❤️", "flags" to "🏳️",
    )

    @Volatile var sections: List<Pair<String, List<String>>>? = null
        private set

    fun load(ctx: Context) {
        if (sections != null) return
        val p = Paint()
        val map = LinkedHashMap<String, MutableList<String>>()
        CATEGORIES.forEach { map[it.first] = ArrayList() }
        ctx.assets.open("emoji.tsv").bufferedReader().useLines { lines ->
            for (ln in lines) {
                val tab = ln.indexOf('\t')
                if (tab < 0) continue
                val e = ln.substring(tab + 1)
                if (p.hasGlyph(e)) map[ln.substring(0, tab)]?.add(e)
            }
        }
        sections = CATEGORIES.map { (k, icon) -> icon to map[k].orEmpty() }
    }
}

object EmojiRecents {
    private const val KEY = "emoji_recents"
    private const val MAX = 32

    fun get(ctx: Context): List<String> =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, "")!!
            .split('\n').filter { it.isNotEmpty() }

    fun add(ctx: Context, e: String) {
        val l = (listOf(e) + get(ctx).filter { it != e }).take(MAX)
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(KEY, l.joinToString("\n")).apply()
    }
}

@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onEmoji(e: String)
        fun onEmojiDelete()
        fun onEmojiClose()
    }

    var theme = KbTheme(false)
        set(v) { field = v; invalidate() }
    var backLabel = "あいう"

    private class Section(val icon: String, val items: List<String>, var startCol: Int = 0)

    private var sections: List<Section> = emptyList()
    private var columns: List<Array<String?>> = emptyList()
    private var recents: List<String> = emptyList()

    private val dp = resources.displayMetrics.density
    private val topPad = 6 * dp
    private val catH = 46 * dp
    private val bottomH = 44 * dp
    private val sidePad = 10 * dp
    private var cellW = 0f
    private var cellH = 0f
    private var gridH = 0f
    private var scroll = 0f
    private var maxScroll = 0f

    private val scroller = OverScroller(context)
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private val emojiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        Thread {
            EmojiData.load(context.applicationContext)
            post { rebuild() }
        }.start()
    }

    /** パネルを開く時に呼ぶ。履歴を読み直して先頭へ */
    fun show(recent: List<String>) {
        recents = recent
        scroller.forceFinished(true)
        scroll = 0f
        rebuild()
    }

    private fun rebuild() {
        val data = EmojiData.sections ?: emptyList()
        sections = listOf(Section("🕘", recents)) + data.map { Section(it.first, it.second) }
        val cols = ArrayList<Array<String?>>()
        for (s in sections) {
            s.startCol = cols.size
            s.items.chunked(ROWS).forEach { ch -> cols.add(Array(ROWS) { ch.getOrNull(it) }) }
        }
        columns = cols
        updateMax()
        invalidate()
    }

    private fun updateMax() {
        maxScroll = (columns.size * cellW + sidePad * 2 - width).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 親 FrameLayout の高さ (= キーボード部) に合わせる。仮計測では 0 を返す
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        gridH = (h - topPad - catH - bottomH).coerceAtLeast(0f)
        cellH = gridH / ROWS
        cellW = (w - sidePad * 2) / 7.4f   // 8列目が少し覗く (iOS と同じ)
        emojiP.textSize = minOf(cellW, cellH) * 0.66f
        iconP.textSize = 19 * dp
        updateMax()
    }

    // ---- 描画 ----

    override fun onDraw(c: Canvas) {
        if (columns.isEmpty() || cellW <= 0f) {
            if (EmojiData.sections == null) text(c, "読み込み中…", width / 2f, topPad + gridH / 2, 15 * dp, theme.disabled)
        } else drawGrid(c)
        drawCategoryBar(c)
        text(c, backLabel, sidePad + 32 * dp, height - bottomH / 2, 17 * dp, theme.text)
    }

    private fun drawGrid(c: Canvas) {
        c.save()
        c.clipRect(0f, topPad, width.toFloat(), topPad + gridH)
        val first = floor((scroll - sidePad) / cellW).toInt().coerceAtLeast(0)
        val last = (first + (width / cellW).toInt() + 2).coerceAtMost(columns.size - 1)
        val fm = emojiP.fontMetrics
        for (col in first..last) {
            val x = sidePad + col * cellW - scroll + cellW / 2
            columns[col].forEachIndexed { row, e ->
                if (e == null) return@forEachIndexed
                val y = topPad + row * cellH + cellH / 2
                c.drawText(e, x, y - (fm.ascent + fm.descent) / 2, emojiP)
            }
        }
        c.restore()
    }

    private val delW get() = 52 * dp
    private fun iconSpacing() = (width - sidePad * 2 - delW) / sections.size.coerceAtLeast(1)

    private fun currentSection(): Int {
        var cur = 0
        sections.forEachIndexed { i, s ->
            if (s.items.isNotEmpty() && s.startCol * cellW <= scroll + cellW * 0.5f) cur = i
        }
        if (scroll >= maxScroll - 1 && maxScroll > 0) cur = sections.lastIndex
        return cur
    }

    private fun drawCategoryBar(c: Canvas) {
        val cy = topPad + gridH + catH / 2
        val sp = iconSpacing()
        val cur = currentSection()
        val fm = iconP.fontMetrics
        sections.forEachIndexed { i, s ->
            val cx = sidePad + sp * i + sp / 2
            if (i == cur) {
                fill.color = theme.pressed
                c.drawCircle(cx, cy, 17 * dp, fill)
            }
            iconP.alpha = if (i == cur) 255 else 200
            c.drawText(s.icon, cx, cy - (fm.ascent + fm.descent) / 2, iconP)
        }
        drawBackspace(c, width - sidePad - delW / 2, cy, 26 * dp, theme.text)
    }

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        fill.textSize = size
        fill.color = color
        fill.textAlign = Paint.Align.CENTER
        val fm = fill.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, fill)
    }

    private fun drawBackspace(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 1.7f * dp
        val h = s * 0.36f
        path.reset()
        path.moveTo(cx - s / 2, cy)
        path.lineTo(cx - s * 0.18f, cy - h); path.lineTo(cx + s / 2, cy - h)
        path.lineTo(cx + s / 2, cy + h); path.lineTo(cx - s * 0.18f, cy + h); path.close()
        val xc = cx + s * 0.14f
        val x = s * 0.13f
        path.moveTo(xc - x, cy - x); path.lineTo(xc + x, cy + x)
        path.moveTo(xc + x, cy - x); path.lineTo(xc - x, cy + x)
        c.drawPath(path, stroke)
    }

    // ---- タッチ ----

    private enum class Region { GRID, CATEGORY, DELETE, BACK, NONE }

    private var region = Region.NONE
    private var x0 = 0f
    private var y0 = 0f
    private var lastX = 0f
    private var dragging = false

    private val repeat = object : Runnable {
        override fun run() {
            listener.onEmojiDelete()
            handler.postDelayed(this, 60)
        }
    }

    private fun regionAt(x: Float, y: Float) = when {
        y < topPad + gridH -> Region.GRID
        y < topPad + gridH + catH -> if (x > width - sidePad - delW) Region.DELETE else Region.CATEGORY
        x < width / 3f -> Region.BACK
        else -> Region.NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; lastX = e.x
                dragging = false
                scroller.forceFinished(true)
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
                region = regionAt(e.x, e.y)
                if (region == Region.DELETE) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onEmojiDelete()
                    handler.postDelayed(repeat, 400)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (region == Region.GRID) {
                    if (!dragging && abs(e.x - x0) > slop) dragging = true
                    if (dragging) {
                        scroll = (scroll - (e.x - lastX)).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeat)
                vt?.addMovement(e)
                when (region) {
                    Region.GRID -> if (dragging) fling() else tapEmoji(e.x, e.y)
                    Region.CATEGORY -> {
                        val i = ((e.x - sidePad) / iconSpacing()).toInt()
                        sections.getOrNull(i)?.let { jumpTo(it) }
                    }
                    Region.BACK -> listener.onEmojiClose()
                    else -> {}
                }
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeat)
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun tapEmoji(x: Float, y: Float) {
        val col = floor((x + scroll - sidePad) / cellW).toInt()
        val row = floor((y - topPad) / cellH).toInt()
        val em = columns.getOrNull(col)?.getOrNull(row) ?: return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener.onEmoji(em)
    }

    private fun fling() {
        val v = vt ?: return
        v.computeCurrentVelocity(1000)
        scroller.fling(scroll.toInt(), 0, -v.xVelocity.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
        postInvalidateOnAnimation()
    }

    private fun jumpTo(s: Section) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val target = (s.startCol * cellW).coerceIn(0f, maxScroll)
        scroller.forceFinished(true)
        scroller.startScroll(scroll.toInt(), 0, (target - scroll).toInt(), 0, 250)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currX.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val ROWS = 4
    }
}
