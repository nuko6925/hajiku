package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** iOS の「パスワードを自動入力」シート風の色 */
class SheetTheme(ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val sheet = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
    val card = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val secondary = if (dark) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
    val separator = if (dark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
    val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val avatar = if (dark) 0xFF636366.toInt() else 0xFF8E8E93.toInt()
    val field = if (dark) 0xFF3A3A3C.toInt() else 0xFFFFFFFF.toInt()
}

/** 丸ボタン (× / +) */
private class CircleIcon(context: Context, private val plus: Boolean, private val t: SheetTheme) : View(context) {
    private val dp = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.card }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = t.text; style = Paint.Style.STROKE; strokeWidth = 2.2f * dp; strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, minOf(cx, cy), bg)
        val s = 8 * dp
        if (plus) {
            c.drawLine(cx - s, cy, cx + s, cy, p); c.drawLine(cx, cy - s, cx, cy + s, p)
        } else {
            val d = s * 0.75f
            c.drawLine(cx - d, cy - d, cx + d, cy + d, p); c.drawLine(cx + d, cy - d, cx - d, cy + d, p)
        }
    }
}

/** ⓘ */
private class InfoIcon(context: Context, private val color: Int) : View(context) {
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f * dp }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textAlign = Paint.Align.CENTER; textSize = 15 * dp; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, 11 * dp, p)
        c.drawText("i", cx, cy - (f.ascent() + f.descent()) / 2, f)
    }
}

/** 🔍 */
private class SearchIcon(context: Context, private val color: Int) : View(context) {
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2.2f * dp; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f - 2 * dp
        val cy = height / 2f - 2 * dp
        val r = 7 * dp
        c.drawCircle(cx, cy, r, p)
        c.drawLine(cx + r * 0.72f, cy + r * 0.72f, cx + r * 1.55f, cy + r * 1.55f, p)
    }
}

/**
 * パスワード選択シート (iOS「パスワードを自動入力」風)。
 * 上: 説明文 / × タイトル + 、中: このサイトのもの → その他 (五十音・ABC順) のカード、下: 浮いた検索欄
 */
@SuppressLint("ViewConstructor")
class VaultPickerView(
    context: Context,
    site: String?,
    private val matching: List<VaultEntry>,
    private val others: List<VaultEntry>,
    private val onPick: (VaultEntry) -> Unit,
    private val onInfo: (VaultEntry) -> Unit,
    onClose: () -> Unit,
    onAdd: () -> Unit,
) : FrameLayout(context) {
    private val t = SheetTheme(context)
    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val search: EditText

    init {
        background = GradientDrawable().apply {
            setColor(t.sheet)
            val r = 38 * dp
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        if (!site.isNullOrEmpty()) column.addView(TextView(context).apply {
            text = "“$site” で使用するパスワードを選択してください。"
            textSize = 13f
            setTextColor(t.secondary)
            gravity = Gravity.CENTER
            setPadding(px(20f), px(18f), px(20f), 0)
        })

        // × パスワードを自動入力 +
        column.addView(FrameLayout(context).apply {
            setPadding(px(16f), px(12f), px(16f), px(8f))
            addView(CircleIcon(context, false, t).apply { setOnClickListener { onClose() } },
                LayoutParams(px(44f), px(44f), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(TextView(context).apply {
                text = "パスワードを自動入力"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(t.text)
                gravity = Gravity.CENTER
            }, LayoutParams(LayoutParams.MATCH_PARENT, px(44f), Gravity.CENTER))
            addView(CircleIcon(context, true, t).apply { setOnClickListener { onAdd() } },
                LayoutParams(px(44f), px(44f), Gravity.END or Gravity.CENTER_VERTICAL))
        })

        column.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            // 下の検索欄に隠れないよう余白
            setPadding(0, 0, 0, px(96f))
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 下に浮いた検索欄
        search = EditText(context).apply {
            hint = "検索"
            textSize = 17f
            setTextColor(t.text)
            setHintTextColor(t.secondary)
            background = null
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(t.field); cornerRadius = 30 * dp }
            elevation = 6 * dp
            setPadding(px(16f), 0, px(16f), 0)
            addView(SearchIcon(context, t.text), LinearLayout.LayoutParams(px(28f), px(28f)))
            addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = px(8f) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, px(58f), Gravity.BOTTOM).apply {
            setMargins(px(24f), 0, px(24f), px(20f))
        })
        render()
    }

    private fun render() {
        content.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        fun f(l: List<VaultEntry>) = if (q.isEmpty()) l else l.filter {
            it.title.lowercase().contains(q) || it.username.lowercase().contains(q)
        }
        val m = f(matching)
        val o = f(others).sortedWith(compareBy({ it.title.lowercase() }, { it.username.lowercase() }))
        if (m.isEmpty() && o.isEmpty()) {
            content.addView(TextView(context).apply {
                text = if (q.isEmpty()) "保存されたパスワードはありません" else "一致する項目はありません"
                setTextColor(t.secondary); gravity = Gravity.CENTER; setPadding(0, px(40f), 0, 0)
            })
            return
        }
        if (m.isNotEmpty()) content.addView(card(m))
        if (o.isNotEmpty()) content.addView(card(o))
    }

    private fun card(list: List<VaultEntry>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(t.card); cornerRadius = 26 * dp }
        clipToOutline = true
        list.forEachIndexed { i, e ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(t.separator) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, px(0.5f))).apply { leftMargin = px(84f) })
            addView(row(e))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(px(18f), px(14f), px(18f), px(6f))
        }
    }

    private fun row(e: VaultEntry): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(18f), px(12f), px(10f), px(12f))
        isClickable = true
        setOnClickListener { onPick(e) }
        addView(avatar(e), LinearLayout.LayoutParams(px(50f), px(50f)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = e.title; textSize = 17f; setTextColor(t.text); isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = e.username.ifEmpty { "(ユーザー名なし)" }; textSize = 15f; setTextColor(t.secondary); isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(16f) })
        addView(InfoIcon(context, t.blue).apply { setOnClickListener { onInfo(e) } },
            LinearLayout.LayoutParams(px(44f), px(44f)))
    }

    /** アプリならそのアイコン、サイトなら頭文字 (iOS の灰色の頭文字アイコン) */
    private fun avatar(e: VaultEntry): View {
        if (e.pkg.isNotEmpty()) {
            runCatching { context.packageManager.getApplicationIcon(e.pkg) }.getOrNull()?.let { d ->
                return ImageView(context).apply {
                    setImageDrawable(d)
                    background = GradientDrawable().apply { cornerRadius = 11 * dp; setColor(0) }
                    clipToOutline = true
                }
            }
        }
        val letter = e.title.removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        return TextView(context).apply {
            text = letter
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(t.avatar); cornerRadius = 11 * dp }
        }
    }
}
