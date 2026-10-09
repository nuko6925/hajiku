package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 🔑 で開くキーボード内のパスワード一覧 (iOS と同じく画面を切り替えない)。
 * このサイト / アプリのものを先頭に、その下にその他
 */
@SuppressLint("ViewConstructor")
class VaultPanelView(
    context: Context,
    private val onPick: (VaultEntry) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val header = TextView(context).apply {
        text = "パスワード"
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), 0, 0, 0)
    }
    private val close = ChevronView(context, up = false).apply { setOnClickListener { onClose() } }

    var theme = KbTheme(false)
        set(v) {
            field = v
            setBackgroundColor(v.bg)
            header.setTextColor(v.text)
            close.color = v.text
        }

    init {
        orientation = VERTICAL
        addView(FrameLayout(context).apply {
            addView(header, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(close, FrameLayout.LayoutParams((48 * dp).toInt(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
        }, LayoutParams(LayoutParams.MATCH_PARENT, (44 * dp).toInt()))
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 親の仮計測では高さ 0 (EmojiPanelView と同じ理由)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) heightMeasureSpec
        else MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, h)
    }

    fun set(matching: List<VaultEntry>, others: List<VaultEntry>) {
        list.removeAllViews()
        if (matching.isEmpty() && others.isEmpty()) {
            list.addView(label("保存されたパスワードはありません（Hajiku アプリ → パスワード で追加）"))
            return
        }
        if (matching.isNotEmpty()) {
            list.addView(label("このサイト / アプリ"))
            matching.forEach { list.addView(row(it)) }
        }
        if (others.isNotEmpty()) {
            list.addView(label("その他"))
            others.forEach { list.addView(row(it)) }
        }
    }

    private fun label(s: String) = TextView(context).apply {
        text = s
        textSize = 12f
        setTextColor(theme.disabled)
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
    }

    private fun row(e: VaultEntry): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        isClickable = true
        setOnClickListener { onPick(e) }
        addView(TextView(context).apply { text = e.username.ifEmpty { "(ユーザー名なし)" }; textSize = 17f; setTextColor(theme.text) })
        addView(TextView(context).apply { text = e.title; textSize = 12f; setTextColor(theme.disabled) })
    }
}
