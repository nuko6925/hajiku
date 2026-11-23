package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 片手モード (iOS と同じ): キーボードを左右に寄せ、空いた側に「戻す」矢印を出す。
 * キーの高さは全幅のときと同じにする (KeyboardView.heightBasisWidth)
 */
@SuppressLint("ViewConstructor")
class OneHandLayout(ctx: Context, private val kb: KeyboardView, private val onBack: () -> Unit) : ViewGroup(ctx) {
    private val dp = ctx.resources.displayMetrics.density

    /** 0 = 中央, 1 = 左寄せ, 2 = 右寄せ */
    var side = 0
        set(v) { if (field != v) { field = v; chevron.invalidate(); requestLayout() } }

    var chevronColor = 0x80888888.toInt()
        set(v) { field = v; chevron.invalidate() }

    /** 空いた側の「‹ / ›」。押すと中央に戻す */
    private val chevron = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        override fun onDraw(c: Canvas) {
            p.color = chevronColor
            p.strokeWidth = 4 * dp
            val cx = width / 2f
            val cy = height / 2f
            val w = 7 * dp
            val h = 14 * dp
            path.reset()
            // 左寄せなら右側に「›」、右寄せなら左側に「‹」(押すと中央に戻る向き)
            val d = if (side == 1) 1 else -1
            path.moveTo(cx - d * w / 2, cy - h)
            path.lineTo(cx + d * w / 2, cy)
            path.lineTo(cx - d * w / 2, cy + h)
            c.drawPath(path, p)
        }
    }.apply {
        setOnClickListener { onBack() }
        contentDescription = "キーボードを中央に戻す"
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(kb)
        addView(chevron)
    }

    private fun kbWidth(w: Int) = if (side == 0) w else (w * RATIO).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        kb.heightBasisWidth = w
        val kw = kbWidth(w)
        kb.measure(MeasureSpec.makeMeasureSpec(kw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = kb.measuredHeight
        chevron.visibility = if (side == 0) GONE else VISIBLE
        chevron.measure(MeasureSpec.makeMeasureSpec((w - kw).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val kw = kbWidth(w)
        val h = kb.measuredHeight
        if (side == 2) {
            kb.layout(w - kw, 0, w, h)
            chevron.layout(0, 0, w - kw, h)
        } else {
            kb.layout(0, 0, kw, h)
            chevron.layout(kw, 0, w, h)
        }
    }

    companion object {
        /** 片手モードのキーボードの幅 (全幅に対する割合) */
        const val RATIO = 0.8f
    }
}

/**
 * 絵文字キーの長押しメニュー (iOS の地球儀キー長押しと同じ)。
 * 長押ししたまま指をなぞって離すか、離した後にタップで選ぶ。メニューの外をタップすると閉じる
 */
class KbMenuView(ctx: Context, private val actions: Actions) : FrameLayout(ctx) {
    interface Actions {
        fun openSettings()
        fun switchInputMethod()
        fun openEmoji()
        fun setOneHand(side: Int)
    }

    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    private var theme = KbTheme(false)
    private var current = 0

    /** なぞって選べる項目 (文字の行と、寄せ方のアイコン 3 つ) */
    private class Item(val view: View, val run: () -> Unit)
    private val items = mutableListOf<Item>()
    private var hover: Item? = null

    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(10f), px(10f), px(10f), px(10f))
        elevation = 8 * dp
    }

    init {
        visibility = GONE
        clipChildren = false
        addView(panel, LayoutParams(px(240f), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
    }

    /** メニューを開く。left / bottom: パネルの左端と下端 (このビュー内の座標) */
    fun show(t: KbTheme, oneHand: Int, left: Int, bottom: Int) {
        theme = t
        current = oneHand
        build()
        val lp = panel.layoutParams as LayoutParams
        lp.leftMargin = left.coerceAtLeast(px(6f))
        lp.bottomMargin = (height - bottom).coerceAtLeast(0)
        panel.layoutParams = lp
        hover = null
        visibility = VISIBLE
        refresh()
    }

    fun hide() {
        visibility = GONE
        hover = null
    }

    val isOpen get() = visibility == VISIBLE

    private fun build() {
        panel.removeAllViews()
        items.clear()
        panel.background = GradientDrawable().apply { setColor(theme.popup); cornerRadius = 14 * dp }
        fun row(label: String, run: () -> Unit) {
            val v = TextView(context).apply {
                text = label; textSize = 17f; gravity = Gravity.CENTER
                setTextColor(theme.text)
                setPadding(0, px(12f), 0, px(12f))
            }
            panel.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            items += Item(v, run)
            v.setOnClickListener { pick(items.first { it.view === v }) }
        }
        row("キーボードの設定…") { actions.openSettings() }
        row("入力方法を切り替え…") { actions.switchInputMethod() }
        row("絵文字") { actions.openEmoji() }
        val icons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(6f), 0, 0)
        }
        for (side in intArrayOf(1, 0, 2)) {
            val v = SideIcon(context, side)
            icons.addView(v, LinearLayout.LayoutParams(0, px(48f), 1f))
            val item = Item(v) { actions.setOneHand(side) }
            items += item
            v.setOnClickListener { pick(item) }
        }
        panel.addView(icons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** 背景・文字色を、なぞっている項目と今の寄せ方に合わせる */
    private fun refresh() {
        for (item in items) {
            val on = item === hover
            val v = item.view
            if (v is SideIcon) {
                v.selected(on || (hover == null && v.side == current), theme)
            } else if (v is TextView) {
                v.background = if (on) GradientDrawable().apply { setColor(theme.accent); cornerRadius = 6 * dp } else null
                v.setTextColor(if (on) theme.onAccent else theme.text)
            }
        }
    }

    /** キーボード側から: 長押ししたまま指をなぞっている (画面座標) */
    fun dragTo(rawX: Float, rawY: Float, up: Boolean) {
        if (!isOpen) return
        val loc = IntArray(2)
        val h = items.firstOrNull { item ->
            item.view.getLocationOnScreen(loc)
            rawX >= loc[0] && rawX < loc[0] + item.view.width && rawY >= loc[1] && rawY < loc[1] + item.view.height
        }
        if (h !== hover) {
            hover = h
            if (h != null) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            refresh()
        }
        // 項目の上で離したら実行。何も無い所で離したらメニューは開いたまま (タップで選べる)
        if (up) h?.let { pick(it) }
    }

    private fun pick(item: Item) {
        hide()
        item.run()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // パネルの外 (このビュー自身に届いたタッチ) は閉じる
        if (e.actionMasked == MotionEvent.ACTION_DOWN) hide()
        return true
    }

    /** 寄せ方のアイコン: キーボードの絵 + 寄せる側の三角 */
    private class SideIcon(ctx: Context, val side: Int) : View(ctx) {
        private val dp = ctx.resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val tri = Path()
        private var sel = false
        private var t = KbTheme(false)

        fun selected(on: Boolean, theme: KbTheme) {
            sel = on; t = theme; invalidate()
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            if (sel) {
                fill.color = t.accent
                r.set(cx - 30 * dp, cy - 20 * dp, cx + 30 * dp, cy + 20 * dp)
                c.drawRoundRect(r, 20 * dp, 20 * dp, fill)
            }
            val col = if (sel) t.onAccent else t.text
            stroke.color = col
            fill.color = col
            stroke.strokeWidth = 1.8f * dp
            // 寄せる側を空けてキーボードの枠を描く
            val w = 26 * dp
            val h = 16 * dp
            val shift = when (side) { 1 -> 3 * dp; 2 -> -3 * dp; else -> 0f }
            r.set(cx - w / 2 + shift, cy - h / 2, cx + w / 2 + shift, cy + h / 2)
            c.drawRoundRect(r, 3 * dp, 3 * dp, stroke)
            // キーの点 3 段
            val dot = 1.6f * dp
            for (row in 0 until 3) for (col2 in 0 until 6) {
                val x = r.left + (col2 + 1) * r.width() / 7
                val y = r.top + (row + 1) * r.height() / 4
                if (row == 2 && col2 in 1..4) continue
                c.drawCircle(x, y, dot / 1.4f, fill)
            }
            c.drawLine(r.left + r.width() * 0.3f, r.bottom - r.height() / 4, r.right - r.width() * 0.3f, r.bottom - r.height() / 4, stroke)
            // 三角 (左寄せは左向き、右寄せは右向き)
            if (side != 0) {
                tri.reset()
                val d = if (side == 1) -1 else 1
                val tx = if (side == 1) r.left - 2 * dp else r.right + 2 * dp
                tri.moveTo(tx, cy - 5 * dp)
                tri.lineTo(tx + d * 6 * dp, cy)
                tri.lineTo(tx, cy + 5 * dp)
                tri.close()
                c.drawPath(tri, fill)
            }
        }
    }
}
