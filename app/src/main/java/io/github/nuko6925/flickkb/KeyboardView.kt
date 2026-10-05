package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

data class UiState(
    val composing: Boolean = false,
    val toggling: Boolean = false,
    /** ↺ / ↻ が使えるか */
    val cycleEnabled: Boolean = false,
    /** 小゛゜ が効く文字が末尾にある */
    val modifiable: Boolean = false,
)

class KbTheme(dark: Boolean) {
    val bg = if (dark) 0xFF2B2B2D.toInt() else 0xFFE0E1E6.toInt()
    val key = if (dark) 0xFF5C5C5F.toInt() else 0xFFFFFFFF.toInt()
    val func = key
    val pressed = if (dark) 0xFF3A3A3C.toInt() else 0xFFB4B8C1.toInt()
    val shadow = if (dark) 0x66000000 else 0x55898A8D
    val text = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val disabled = if (dark) 0xFF8E8E93.toInt() else 0xFFA5A5A5.toInt()
    val guide = if (dark) 0xFF3A3A3C.toInt() else 0xFFA9AEB7.toInt()
    val guideText = 0xFFFFFFFF.toInt()
    val candSelected = if (dark) 0xFF5C5C5F.toInt() else 0xFFFFFFFF.toInt()
    val separator = if (dark) 0xFF4A4A4D.toInt() else 0xFFB3B6BD.toInt()
}

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onCharTap(key: Key)
        fun onCharFlick(key: Key, s: String)
        fun onFunction(key: Key)
        /** true を返すと離した時の onFunction を抑止。SPACE で true ならトラックパッドモード */
        fun onFunctionLongPress(key: Key): Boolean
        fun onCursorMove(dx: Int, dy: Int)
    }

    var theme = KbTheme(false)
        set(v) { field = v; invalidate() }

    var mode: Mode = Mode.KANA
        set(v) { field = v; keys = Layouts.of(v); layoutKeys(); invalidate() }

    var state = UiState()
        set(v) { if (field != v) { field = v; invalidate() } }

    private var keys: List<Key> = Layouts.kana
    private val rects = HashMap<Key, RectF>()

    private val dp = resources.displayMetrics.density
    private val margin = 7 * dp
    private val gap = 6 * dp
    private val topPad = 6 * dp
    private val bottomBar = 46 * dp
    private val radius = 8.5f * dp
    private var keyW = 0f
    private var keyH = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val tmp = RectF()

    // ---- 計測・配置 ----

    private fun calcKeySize(width: Int) {
        keyW = (width - 2 * margin - 4 * gap) / 5f
        keyH = (keyW * 0.645f).coerceIn(40 * dp, 54 * dp)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        calcKeySize(w)
        val h = topPad + 4 * keyH + 3 * gap + bottomBar
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        calcKeySize(w)
        layoutKeys()
    }

    private fun layoutKeys() {
        rects.clear()
        if (keyW <= 0f) return
        for (k in keys) {
            if (k.type == KeyType.EMOJI) {
                val cx = margin + keyW / 2
                val cy = topPad + 4 * keyH + 3 * gap + bottomBar / 2
                val s = 22 * dp
                rects[k] = RectF(cx - s, cy - s, cx + s, cy + s)
                continue
            }
            val l = margin + k.col * (keyW + gap)
            val t = topPad + k.row * (keyH + gap)
            rects[k] = RectF(l, t, l + k.colSpan * keyW + (k.colSpan - 1) * gap,
                t + k.rowSpan * keyH + (k.rowSpan - 1) * gap)
        }
    }

    // ---- タッチ ----

    private class Touch(val id: Int, val key: Key, val x0: Float, val y0: Float) {
        var dir = Dir.C
        var guide = false
        var longPressed = false
        var trackpad = false
        var lastX = x0
        var lastY = y0
        var accX = 0f
        var accY = 0f
    }

    private var active: Touch? = null
    private val handler = Handler(Looper.getMainLooper())

    private val longRunnable = Runnable {
        val t = active ?: return@Runnable
        if (t.key.type == KeyType.CHAR) {
            t.guide = true
            invalidate()
        } else if (listener.onFunctionLongPress(t.key)) {
            t.longPressed = true
            if (t.key.type == KeyType.SPACE) {
                t.trackpad = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val t = active ?: return
            listener.onFunction(t.key)
            handler.postDelayed(this, 60)
        }
    }

    private fun hit(x: Float, y: Float): Key? {
        rects.entries.firstOrNull { it.value.contains(x, y) }?.let { return it.key }
        // 隙間タップは最寄りのキーへ
        var best: Key? = null
        var bd = Float.MAX_VALUE
        for ((k, r) in rects) {
            val d = hypot(x - x.coerceIn(r.left, r.right), y - y.coerceIn(r.top, r.bottom))
            if (d < bd) { bd = d; best = k }
        }
        return if (bd < gap * 2) best else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 前の指が残っていれば先に確定 (高速入力のロールオーバー)
                active?.let { release(it) }
                val i = e.actionIndex
                val k = hit(e.getX(i), e.getY(i)) ?: return true
                val t = Touch(e.getPointerId(i), k, e.getX(i), e.getY(i))
                active = t
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (k.type == KeyType.DELETE) {
                    listener.onFunction(k)
                    handler.postDelayed(repeatRunnable, 400)
                } else {
                    handler.postDelayed(longRunnable, if (k.type == KeyType.CHAR) 350 else 500)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val t = active ?: return true
                val i = e.findPointerIndex(t.id)
                if (i < 0) return true
                val x = e.getX(i)
                val y = e.getY(i)
                if (t.trackpad) {
                    trackpadMove(t, x, y)
                    return true
                }
                t.lastX = x
                t.lastY = y
                if (t.key.type != KeyType.CHAR) return true
                val d = direction(t, x, y)
                if (d != t.dir) {
                    t.dir = d
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = active ?: return true
                if (e.getPointerId(e.actionIndex) == t.id) release(t)
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacksAndMessages(null)
                active = null
                invalidate()
            }
        }
        return true
    }

    private fun trackpadMove(t: Touch, x: Float, y: Float) {
        t.accX += x - t.lastX
        t.accY += y - t.lastY
        t.lastX = x
        t.lastY = y
        val stepX = 9 * dp
        val stepY = keyH * 0.8f
        var moved = false
        while (t.accX >= stepX) { listener.onCursorMove(1, 0); t.accX -= stepX; moved = true }
        while (t.accX <= -stepX) { listener.onCursorMove(-1, 0); t.accX += stepX; moved = true }
        while (t.accY >= stepY) { listener.onCursorMove(0, 1); t.accY -= stepY; moved = true }
        while (t.accY <= -stepY) { listener.onCursorMove(0, -1); t.accY += stepY; moved = true }
        if (moved) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun direction(t: Touch, x: Float, y: Float): Int {
        val dx = x - t.x0
        val dy = y - t.y0
        val th = min(keyW, keyH) * 0.33f
        val d = when {
            hypot(dx, dy) < th -> Dir.C
            abs(dx) > abs(dy) -> if (dx < 0) Dir.L else Dir.R
            else -> if (dy < 0) Dir.U else Dir.D
        }
        return if (t.key.flick[d] == null) Dir.NONE else d
    }

    private fun release(t: Touch) {
        handler.removeCallbacksAndMessages(null)
        active = null
        val k = t.key
        when (k.type) {
            KeyType.CHAR -> when (t.dir) {
                Dir.NONE -> {}
                Dir.C -> listener.onCharTap(k)
                else -> listener.onCharFlick(k, k.flick[t.dir]!!)
            }
            KeyType.DELETE -> {}
            else -> if (!t.longPressed) listener.onFunction(k)
        }
        invalidate()
    }

    // ---- 描画 ----

    override fun onDraw(c: Canvas) {
        // 背景は親 (角丸パネル) が描く
        for (k in keys) drawKey(c, k)
        drawGuide(c)
    }

    private fun drawKey(c: Canvas, k: Key) {
        val r = rects[k] ?: return
        val t = active
        val pressed = t?.key === k
        if (k.type == KeyType.EMOJI) {
            drawEmoji(c, r.centerX(), r.centerY(), 13 * dp, theme.text)
            return
        }
        // 影 → キー面
        fill.color = theme.shadow
        tmp.set(r); tmp.offset(0f, 1 * dp)
        c.drawRoundRect(tmp, radius, radius, fill)
        fill.color = if (pressed) theme.pressed else if (k.isFunction) theme.func else theme.key
        c.drawRoundRect(r, radius, radius, fill)
        if (t?.trackpad == true) return  // トラックパッド中はラベルを消す (iOS と同じ)

        val cx = r.centerX()
        val cy = r.centerY()
        val s = keyH
        when (k.type) {
            KeyType.CHAR -> {
                if (k.sub != null) {
                    text(c, k.label, cx, cy - s * 0.12f, s * 0.44f, theme.text)
                    text(c, k.sub, cx, cy + s * 0.25f, s * 0.24f, theme.text)
                } else {
                    val latin = k.label.first().code < 0x3000
                    text(c, k.label, cx, cy, if (latin) s * 0.36f else s * 0.47f, theme.text,
                        spacing = if (latin) 0.14f else 0f)
                }
            }
            KeyType.CURSOR_NEXT -> drawArrow(c, cx, cy, s * 0.42f, if (state.toggling) theme.text else theme.disabled)
            KeyType.TOGGLE_BACK -> drawUndo(c, cx, cy, s * 0.36f, if (state.cycleEnabled) theme.text else theme.disabled, false)
            KeyType.TOGGLE_FWD -> drawUndo(c, cx, cy, s * 0.36f, if (state.cycleEnabled) theme.text else theme.disabled, true)
            KeyType.MODE -> text(c, k.label, cx, cy, s * 0.36f, theme.text)
            KeyType.DELETE -> drawBackspace(c, cx, cy, s * 0.5f, theme.text)
            KeyType.SPACE -> text(c, if (state.composing) "次候補" else "空白", cx, cy, s * 0.34f, theme.text)
            KeyType.ENTER ->
                if (state.composing) text(c, "確定", cx, cy, s * 0.34f, theme.text)
                else drawEnter(c, cx, cy, s * 0.6f, theme.text)
            KeyType.MODIFIER ->
                if (state.modifiable) text(c, "小゛゜", cx, cy, s * 0.36f, theme.text)
                else text(c, "^_^", cx, cy, s * 0.36f, theme.text)
            KeyType.CASE -> text(c, k.label, cx, cy, s * 0.36f, theme.text, spacing = 0.1f)
            KeyType.EMOJI -> {}
        }
    }

    /** フリック中は選択方向のポップアップ、長押しで十字ガイド */
    private fun drawGuide(c: Canvas) {
        val t = active ?: return
        val k = t.key
        if (k.type != KeyType.CHAR) return
        val r = rects[k] ?: return
        if (t.dir != Dir.C && t.dir != Dir.NONE) {
            tile(c, r, t.dir, k.flick[t.dir]!!, selected = true)
        } else if (t.guide) {
            for (d in Dir.L..Dir.D) k.flick[d]?.let { tile(c, r, d, it, selected = false) }
            tile(c, r, Dir.C, k.flick[Dir.C]!!, selected = true)
        }
    }

    private fun tile(c: Canvas, base: RectF, dir: Int, s: String, selected: Boolean) {
        val w = base.width()
        val h = base.height()
        tmp.set(base)
        when (dir) {
            Dir.L -> tmp.offset(-w, 0f)
            Dir.R -> tmp.offset(w, 0f)
            Dir.U -> tmp.offset(0f, -h)
            Dir.D -> tmp.offset(0f, h)
        }
        fill.color = theme.shadow
        tmp.offset(0f, 1.5f * dp)
        c.drawRoundRect(tmp, radius, radius, fill)
        tmp.offset(0f, -1.5f * dp)
        fill.color = if (selected) theme.key else theme.guide
        c.drawRoundRect(tmp, radius, radius, fill)
        text(c, s, tmp.centerX(), tmp.centerY(), keyH * if (selected) 0.56f else 0.44f,
            if (selected) theme.text else theme.guideText)
    }

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, spacing: Float = 0f) {
        textP.textSize = size
        textP.color = color
        textP.letterSpacing = spacing
        val fm = textP.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, textP)
    }

    private fun prepStroke(color: Int, w: Float) {
        stroke.color = color
        stroke.strokeWidth = w
    }

    private fun drawArrow(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prepStroke(color, 1.6f * dp)
        path.reset()
        path.moveTo(cx - s / 2, cy); path.lineTo(cx + s / 2, cy)
        path.moveTo(cx + s / 2 - s * 0.22f, cy - s * 0.15f); path.lineTo(cx + s / 2, cy)
        path.lineTo(cx + s / 2 - s * 0.22f, cy + s * 0.15f)
        c.drawPath(path, stroke)
    }

    private fun drawUndo(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, mirror: Boolean) {
        prepStroke(color, 1.8f * dp)
        c.save()
        if (mirror) c.scale(-1f, 1f, cx, cy)
        val rad = s / 2
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad)
        // 真上から時計回りに 300°、左上が開く。矢じりは真上で左向き (= 反時計回りの進行方向)
        c.drawArc(tmp, -90f, 300f, false, stroke)
        path.reset()
        val w = rad * 0.42f
        path.moveTo(cx + w, cy - rad - w)
        path.lineTo(cx, cy - rad)
        path.lineTo(cx + w, cy - rad + w)
        c.drawPath(path, stroke)
        c.restore()
    }

    private fun drawBackspace(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prepStroke(color, 1.7f * dp)
        val h = s * 0.36f
        path.reset()
        path.moveTo(cx - s / 2, cy)
        path.lineTo(cx - s * 0.18f, cy - h)
        path.lineTo(cx + s / 2, cy - h)
        path.lineTo(cx + s / 2, cy + h)
        path.lineTo(cx - s * 0.18f, cy + h)
        path.close()
        val xc = cx + s * 0.14f
        val x = s * 0.13f
        path.moveTo(xc - x, cy - x); path.lineTo(xc + x, cy + x)
        path.moveTo(xc + x, cy - x); path.lineTo(xc - x, cy + x)
        c.drawPath(path, stroke)
    }

    private fun drawEnter(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prepStroke(color, 1.7f * dp)
        val l = cx - s / 2
        val r = cx + s / 2
        val by = cy + s * 0.12f
        val ty = cy - s * 0.22f
        path.reset()
        path.moveTo(cx + s * 0.05f, ty); path.lineTo(r, ty); path.lineTo(r, by); path.lineTo(l, by)
        path.moveTo(l + s * 0.2f, by - s * 0.18f); path.lineTo(l, by); path.lineTo(l + s * 0.2f, by + s * 0.18f)
        c.drawPath(path, stroke)
    }

    private fun drawEmoji(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        prepStroke(color, 2.4f * dp)
        c.drawCircle(cx, cy, r, stroke)
        fill.color = color
        c.drawOval(cx - r * 0.42f, cy - r * 0.48f, cx - r * 0.18f, cy - r * 0.08f, fill)
        c.drawOval(cx + r * 0.18f, cy - r * 0.48f, cx + r * 0.42f, cy - r * 0.08f, fill)
        tmp.set(cx - r * 0.6f, cy - r * 0.35f, cx + r * 0.6f, cy + r * 0.62f)
        c.drawArc(tmp, 0f, 180f, true, fill)
    }
}
