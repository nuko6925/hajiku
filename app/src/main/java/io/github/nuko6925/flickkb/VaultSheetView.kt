package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** iOS「パスワード」風の色 */
class SheetTheme(ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val sheet = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
    val card = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val secondary = if (dark) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
    val separator = if (dark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
    val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    val avatar = if (dark) 0xFF636366.toInt() else 0xFF8E8E93.toInt()
    val field = if (dark) 0xFF3A3A3C.toInt() else 0xFFFFFFFF.toInt()
}

/** 丸ボタンのアイコン */
private class CircleIcon(context: Context, private val kind: Int, private val t: SheetTheme) : View(context) {
    private val dp = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.card }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = t.text; style = Paint.Style.STROKE; strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, minOf(cx, cy), bg)
        val s = 8 * dp
        when (kind) {
            PLUS -> { c.drawLine(cx - s, cy, cx + s, cy, p); c.drawLine(cx, cy - s, cx, cy + s, p) }
            CLOSE -> { val d = s * 0.75f; c.drawLine(cx - d, cy - d, cx + d, cy + d, p); c.drawLine(cx + d, cy - d, cx - d, cy + d, p) }
            BACK -> { c.drawLine(cx + s * 0.3f, cy - s, cx - s * 0.5f, cy, p); c.drawLine(cx - s * 0.5f, cy, cx + s * 0.3f, cy + s, p) }
        }
    }

    companion object { const val CLOSE = 0; const val PLUS = 1; const val BACK = 2 }
}

private class InfoIcon(context: Context, color: Int) : View(context) {
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

private class SearchIcon(context: Context, color: Int) : View(context) {
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
 * パスワードのシート (iOS「パスワードを自動入力」/「パスワード」アプリ風)。
 * 一覧 → ⓘ で詳細 → 編集、をシート内で横に切り替える。
 * pick = true: 自動入力から。行をタップするとそのアカウントで入力
 */
@SuppressLint("ViewConstructor")
class VaultSheetView(
    context: Context,
    private val pick: Boolean,
    private val site: String?,
    private val domain: String?,
    private val pkg: String?,
    private val host: Host,
) : FrameLayout(context) {

    interface Host {
        fun close()
        /** pick モードでアカウントが選ばれた */
        fun fill(e: VaultEntry)
        /** 認証済み (60 秒以内) なら即実行、切れていれば認証してから */
        fun withAuth(block: () -> Unit)
        /** CSV 読み込み (一覧モードのみ) */
        fun importCsv()
    }

    private val t = SheetTheme(context)
    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private val store = VaultStore.get(context)
    private val pages = ArrayList<View>()
    private val listContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var search: EditText
    private val dateFmt = SimpleDateFormat("yyyy/MM/dd", Locale.ROOT)

    init {
        background = GradientDrawable().apply {
            setColor(t.sheet)
            val r = 38 * dp
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        push(listPage(), animate = false)
    }

    // ---- ページ切り替え ----

    private fun push(v: View, animate: Boolean = true) {
        hideKeyboard()
        pages.lastOrNull()?.visibility = View.INVISIBLE
        pages.add(v)
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (animate) { v.translationX = width.toFloat(); v.animate().translationX(0f).setDuration(220).start() }
    }

    private fun pop() {
        hideKeyboard()
        if (pages.size <= 1) return
        val v = pages.removeAt(pages.size - 1)
        v.animate().translationX(width.toFloat()).setDuration(200).withEndAction { removeView(v) }.start()
        pages.last().visibility = View.VISIBLE
    }

    /** 端末の戻る操作: 詳細なら一覧へ。一覧なら false (閉じる) */
    fun back(): Boolean {
        if (pages.size > 1) { pop(); return true }
        return false
    }

    fun reload() = renderList()

    private fun hideKeyboard() {
        val focused = findFocus() ?: return
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    // ---- 部品 ----

    private fun scroll(content: View, bottomPad: Float = 24f) = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, 0, 0, px(bottomPad))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // iOS と同じくスクロールしたらキーボードを閉じる
        setOnScrollChangeListener { _, _, y, _, oldY -> if (y != oldY) hideKeyboard() }
    }

    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(t.card); cornerRadius = 26 * dp }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(px(18f), px(14f), px(18f), px(6f))
        }
    }

    private fun LinearLayout.sep(inset: Float) = addView(View(context).apply { setBackgroundColor(t.separator) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, px(0.5f))).apply { leftMargin = px(inset); rightMargin = px(18f) })

    private fun pill(text: String, bold: Boolean = false, color: Int = t.text, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 17f
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(px(18f), 0, px(18f), 0)
        background = GradientDrawable().apply { setColor(t.card); cornerRadius = 22 * dp }
        setOnClickListener { onClick() }
    }

    private fun topBar(left: View, right: View?, title: String? = null) = FrameLayout(context).apply {
        setPadding(px(16f), px(14f), px(16f), px(8f))
        if (title != null) addView(TextView(context).apply {
            text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(t.text); gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, px(44f), Gravity.CENTER))
        addView(left, LayoutParams(if (left is TextView) LayoutParams.WRAP_CONTENT else px(44f), px(44f), Gravity.START or Gravity.CENTER_VERTICAL))
        if (right != null) addView(right, LayoutParams(if (right is TextView) LayoutParams.WRAP_CONTENT else px(44f), px(44f), Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun avatar(e: VaultEntry?, size: Float, textSp: Float): View {
        if (e != null && e.pkg.isNotEmpty()) {
            runCatching { context.packageManager.getApplicationIcon(e.pkg) }.getOrNull()?.let { d ->
                return ImageView(context).apply { setImageDrawable(d) }
            }
        }
        val letter = e?.title?.removePrefix("www.")?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        return TextView(context).apply {
            text = letter; textSize = textSp; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(t.avatar); cornerRadius = size * 0.22f * dp }
        }
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    private fun copy(label: String, s: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, s)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        toast("コピーしました")
    }

    // ---- 一覧 ----

    private fun listPage(): View {
        val page = FrameLayout(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (pick && !site.isNullOrEmpty()) column.addView(TextView(context).apply {
            text = "“$site” で使用するパスワードを選択してください。"
            textSize = 13f; setTextColor(t.secondary); gravity = Gravity.CENTER
            setPadding(px(20f), px(18f), px(20f), 0)
        })
        column.addView(topBar(
            CircleIcon(context, CircleIcon.CLOSE, t).apply { setOnClickListener { host.close() } },
            CircleIcon(context, CircleIcon.PLUS, t).apply { setOnClickListener { push(editPage(null)) } },
            if (pick) "パスワードを自動入力" else "パスワード"))
        column.addView(scroll(listContent, 96f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        page.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        search = EditText(context).apply {
            hint = "検索"; textSize = 17f; setTextColor(t.text); setHintTextColor(t.secondary)
            background = null; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            // 確定したらキーボードを閉じる (検索語は残す)
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = renderList()
            })
        }
        page.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(t.field); cornerRadius = 30 * dp }
            elevation = 6 * dp
            setPadding(px(16f), 0, px(16f), 0)
            addView(SearchIcon(context, t.text), LinearLayout.LayoutParams(px(28f), px(28f)))
            addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = px(8f) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, px(58f), Gravity.BOTTOM).apply { setMargins(px(24f), 0, px(24f), px(20f)) })
        renderList()
        return page
    }

    private fun renderList() {
        if (!::search.isInitialized) return
        listContent.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val all = store.all()
        val matching = if (pick) store.matching(domain, pkg) else emptyList()
        fun f(l: List<VaultEntry>) = if (q.isEmpty()) l else l.filter {
            it.title.lowercase().contains(q) || it.username.lowercase().contains(q)
        }
        val m = f(matching)
        val o = f(all.filter { e -> matching.none { it.id == e.id } })
            .sortedWith(compareBy({ it.title.lowercase() }, { it.username.lowercase() }))
        if (m.isEmpty() && o.isEmpty()) listContent.addView(TextView(context).apply {
            text = if (q.isEmpty()) "保存されたパスワードはありません" else "一致する項目はありません"
            setTextColor(t.secondary); gravity = Gravity.CENTER; setPadding(0, px(40f), 0, 0)
        })
        for (group in listOf(m, o)) if (group.isNotEmpty()) listContent.addView(card().apply {
            group.forEachIndexed { i, e -> if (i > 0) sep(84f); addView(listRow(e)) }
        })
        if (!pick && q.isEmpty()) listContent.addView(card().apply {
            addView(TextView(context).apply {
                text = "CSV から読み込む…"; textSize = 17f; setTextColor(t.blue)
                setPadding(px(18f), px(16f), px(18f), px(16f))
                setOnClickListener { host.importCsv() }
            })
        })
    }

    private fun listRow(e: VaultEntry): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(18f), px(12f), px(10f), px(12f))
        // 自動入力: 入力 / 一覧: 詳細
        setOnClickListener { if (pick) host.fill(e) else push(detailPage(e)) }
        addView(avatar(e, 50f, 26f), LinearLayout.LayoutParams(px(50f), px(50f)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = e.title; textSize = 17f; setTextColor(t.text); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
            addView(TextView(context).apply {
                text = e.username.ifEmpty { "(ユーザー名なし)" }; textSize = 15f; setTextColor(t.secondary)
                isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(16f) })
        addView(InfoIcon(context, t.blue).apply { setOnClickListener { push(detailPage(e)) } },
            LinearLayout.LayoutParams(px(44f), px(44f)))
    }

    // ---- 詳細 ----

    private fun valueRow(label: String, value: String, valueColor: Int = t.secondary, onClick: (() -> Unit)? = null,
                         onLong: (() -> Unit)? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(58f)
        setPadding(px(18f), px(10f), px(18f), px(10f))
        addView(TextView(context).apply { text = label; textSize = 17f; setTextColor(t.text) })
        addView(TextView(context).apply {
            tag = "value"
            text = value; textSize = 17f; setTextColor(valueColor); gravity = Gravity.END
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(16f) })
        onClick?.let { c -> setOnClickListener { c() } }
        onLong?.let { c -> setOnLongClickListener { c(); true } }
    }

    private fun detailPage(e0: VaultEntry): View {
        val e = store.get(e0.id) ?: e0
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(topBar(
            CircleIcon(context, CircleIcon.BACK, t).apply { setOnClickListener { pop() } },
            pill("編集") { push(editPage(e)) }))
        val masked = "••••••••"
        body.addView(card().apply {
            setPadding(0, px(24f), 0, 0)
            addView(avatar(e, 70f, 38f), LinearLayout.LayoutParams(px(70f), px(70f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(TextView(context).apply {
                text = e.title; textSize = 28f; setTypeface(typeface, Typeface.BOLD); setTextColor(t.text)
                gravity = Gravity.CENTER; setPadding(px(18f), px(10f), px(18f), px(8f))
            })
            addView(valueRow("ユーザ名", e.username, t.text, onClick = { copy("username", e.username, false) }))
            sep(18f)
            var shown = false
            lateinit var pwRow: LinearLayout
            pwRow = valueRow("パスワード", masked, t.text,
                // タップで表示 / 隠す、長押しでコピー
                onClick = {
                    host.withAuth {
                        shown = !shown
                        val v = pwRow.findViewWithTag<TextView>("value")
                        v.text = if (shown) store.password(e.id).orEmpty() else masked
                    }
                },
                onLong = { host.withAuth { copy("password", store.password(e.id).orEmpty(), true) } })
            addView(pwRow)
            sep(18f)
            if (e.domain.isNotEmpty()) addView(valueRow("Webサイト", e.domain))
            else addView(valueRow("アプリ", e.pkg))
            sep(18f)
            addView(valueRow("変更日", if (e.modified > 0) dateFmt.format(Date(e.modified)) else "—"))
        })
        body.addView(TextView(context).apply {
            text = "ユーザ名はタップでコピー、パスワードはタップで表示・長押しでコピー"
            textSize = 12f; setTextColor(t.secondary); setPadding(px(36f), px(4f), px(36f), 0)
        })
        if (pick) body.addView(card().apply {
            addView(TextView(context).apply {
                text = "このパスワードを入力"; textSize = 17f; setTextColor(t.blue)
                setPadding(px(18f), px(16f), px(18f), px(16f))
                setOnClickListener { host.fill(e) }
            })
        })
        return scroll(body)
    }

    // ---- 編集 / 追加 ----

    private fun editRow(label: String, value: String, password: Boolean = false): Pair<LinearLayout, EditText> {
        val et = EditText(context).apply {
            setText(value); textSize = 17f; setTextColor(t.text); background = null; gravity = Gravity.END
            isSingleLine = true
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHintTextColor(t.secondary); hint = "必須"
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(58f)
            setPadding(px(18f), 0, px(18f), 0)
            addView(TextView(context).apply { text = label; textSize = 17f; setTextColor(t.text) })
            addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(16f) })
        }
        return row to et
    }

    /** e == null なら新規 */
    private fun editPage(e: VaultEntry?): View {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val currentPw = if (e != null) runCatching { store.password(e.id) }.getOrNull() else ""
        val isApp = e != null && e.domain.isEmpty() && e.pkg.isNotEmpty()
        val (siteRow, siteEt) = editRow(if (isApp) "アプリ" else "Webサイト", e?.title ?: site.orEmpty())
        val (userRow, userEt) = editRow("ユーザ名", e?.username.orEmpty())
        val (pwRow, pwEt) = editRow("パスワード", currentPw.orEmpty(), password = true)
        if (currentPw == null) pwEt.hint = "変更しない場合は空欄"

        body.addView(topBar(
            pill("キャンセル") { pop() },
            pill("完了", bold = true, color = t.blue) {
                val s = siteEt.text.toString().trim()
                val u = userEt.text.toString()
                val p = pwEt.text.toString()
                if (s.isEmpty() || (e == null && p.isEmpty())) { toast("Webサイトとパスワードを入力してください"); return@pill }
                host.withAuth {
                    val pw = p.ifEmpty { store.password(e!!.id).orEmpty() }
                    if (e == null) store.upsert(if (isApp) "" else s, if (isApp) s else "", u, pw)
                    else store.update(e.id, if (isApp) "" else s, if (isApp) s else "", u, pw)
                    // 編集ページと古い詳細ページを閉じて、新しい内容で詳細を開き直す
                    pop()
                    if (e != null) { pop(); push(detailPage(e), animate = false) }
                    renderList()
                }
            },
            if (e == null) "新規パスワード" else null))
        body.addView(card().apply { addView(siteRow); sep(18f); addView(userRow); sep(18f); addView(pwRow) })
        if (e != null) body.addView(card().apply {
            addView(TextView(context).apply {
                text = "パスワードを削除"; textSize = 17f; setTextColor(t.red)
                setPadding(px(18f), px(16f), px(18f), px(16f))
                setOnClickListener {
                    AlertDialog.Builder(context).setMessage("${e.title} (${e.username}) を削除しますか?")
                        .setPositiveButton("削除") { _, _ ->
                            store.delete(e.id)
                            pop(); pop(); renderList()
                        }
                        .setNegativeButton("キャンセル", null).show()
                }
            })
        })
        return scroll(body)
    }
}
