package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/** 🔑 アイコン */
class KeyIconView(context: Context) : View(context) {
    var color = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val top = height / 2f - 11 * dp
        val r = 5.5f * dp
        p.color = color
        p.style = Paint.Style.FILL
        // 頭 (丸) と穴
        path.reset()
        path.addCircle(cx, top + r, r, Path.Direction.CW)
        path.addCircle(cx, top + r * 0.75f, r * 0.32f, Path.Direction.CCW)
        // 軸
        val w = 2.6f * dp
        path.addRect(cx - w / 2, top + r * 1.8f, cx + w / 2, top + 22 * dp, Path.Direction.CW)
        // 歯
        path.addRect(cx + w / 2, top + 15 * dp, cx + w / 2 + 3 * dp, top + 17.2f * dp, Path.Direction.CW)
        path.addRect(cx + w / 2, top + 19 * dp, cx + w / 2 + 2.2f * dp, top + 21.2f * dp, Path.Direction.CW)
        c.drawPath(path, p)
    }
}

/**
 * パスワード入力時の候補バー: 自動入力サービスのインライン候補 (中央) + 区切り線 + 🔑。
 * 候補をタップするとユーザー名とパスワードが自動入力サービスによって入力される
 */
@SuppressLint("ViewConstructor")
class AutofillBarView(context: Context, onKey: () -> Unit) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }
    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val divider = View(context)
    private val key = KeyIconView(context).apply { setOnClickListener { onKey() } }

    var theme = KbTheme(false)
        set(v) {
            field = v
            divider.setBackgroundColor(v.separator)
            key.color = v.text
        }

    init {
        orientation = HORIZONTAL
        scroll.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(divider, LayoutParams((1 * dp).toInt().coerceAtLeast(1), LayoutParams.MATCH_PARENT).apply {
            setMargins(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        })
        addView(key, LayoutParams((48 * dp).toInt(), LayoutParams.MATCH_PARENT))
    }

    fun setSuggestions(views: List<View>) {
        row.removeAllViews()
        for (v in views) {
            (v.parent as? ViewGroup)?.removeView(v)
            row.addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            })
        }
    }
}
