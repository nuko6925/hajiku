package io.github.nuko6925.flickkb

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.security.keystore.UserNotAuthenticatedException
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 保管庫の一覧。
 * - 通常: 一覧・検索・追加・編集・削除・CSV 取り込み
 * - 選択 (キーボードの 🔑 から): 選んだアカウントをキーボードが入力する
 */
class VaultActivity : Activity() {
    private lateinit var store: VaultStore
    private lateinit var adapter: ArrayAdapter<VaultEntry>
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var entries: List<VaultEntry> = emptyList()
    private var pick = false
    private var targetPkg: String? = null
    private var unlocked = false
    /** 認証画面 (PIN 入力は別画面) やファイル選択で一時的に離れている */
    private var away = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 一覧はスクショ・最近使ったアプリのサムネイルに写さない
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Diag.init(this)
        Diag.log("vault: 起動 pick=${intent.getBooleanExtra(EXTRA_PICK, false)}")
        title = "パスワード"
        store = VaultStore.get(this)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        targetPkg = intent.getStringExtra(EXTRA_PKG)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        if (!unlocked) unlock()
    }

    override fun onStop() {
        super.onStop()
        Diag.log("vault: onStop away=$away")
        if (away) return
        // 離れたら鍵をかける。選択モードは閉じる
        unlocked = false
        adapter.clear()
        if (pick) finish()
    }

    private fun unlock() {
        away = true
        VaultAuth.authenticate(this, "パスワードを表示",
            onOk = { away = false; unlocked = true; reload() },
            onFail = { msg -> away = false; Diag.log("vault: 認証失敗 $msg"); msg?.let { toast(it) }; finish() })
    }

    /** 認証が切れていたら (60 秒経過) もう一度認証してから実行 */
    private fun withAuth(block: () -> Unit) {
        try {
            block()
        } catch (e: UserNotAuthenticatedException) {
            away = true
            VaultAuth.authenticate(this, "パスワードを表示",
                onOk = { away = false; runCatching(block).onFailure { toast("失敗しました") } },
                onFail = { msg -> away = false; msg?.let { toast(it) } })
        } catch (e: Exception) {
            toast("失敗しました: ${e.message}")
        }
    }

    private fun buildUi() {
        val p = (16 * resources.displayMetrics.density).toInt()
        search = EditText(this).apply {
            hint = "検索"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        adapter = object : ArrayAdapter<VaultEntry>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                super.getView(position, convertView, parent).also { v ->
                    val e = getItem(position)!!
                    v.findViewById<TextView>(android.R.id.text1)?.text = e.title
                    v.findViewById<TextView>(android.R.id.text2)?.text = e.username
                }
        }
        empty = TextView(this).apply {
            text = "保存されたパスワードはありません"
            setPadding(p, p * 2, p, p)
            visibility = View.GONE
        }
        val list = ListView(this).apply {
            adapter = this@VaultActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onTap(this@VaultActivity.adapter.getItem(pos)!!) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@VaultActivity).apply { text = "＋ 追加"; setOnClickListener { edit(null) } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!pick) addView(Button(this@VaultActivity).apply { text = "CSV 読み込み"; setOnClickListener { pickCsv() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 2, p, 0)
            if (pick) addView(TextView(this@VaultActivity).apply { text = "入力するアカウントを選んでください"; setPadding(0, 0, 0, p / 2) })
            addView(search)
            addView(buttons)
            addView(empty)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun reload() {
        val all = store.all()
        // 選択モードは今のアプリのものを先頭に
        entries = if (pick && targetPkg != null) all.sortedByDescending { it.pkg == targetPkg } else all
        refresh()
    }

    private fun refresh() {
        if (!unlocked) return
        val q = search.text.toString().trim().lowercase()
        val shown = if (q.isEmpty()) entries else entries.filter {
            it.title.lowercase().contains(q) || it.username.lowercase().contains(q)
        }
        adapter.clear(); adapter.addAll(shown)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onTap(e: VaultEntry) {
        if (pick) {
            withAuth {
                val pw = store.password(e.id) ?: return@withAuth
                store.touch(e.id)
                PendingFill.set(e.username, pw)
                finish()
            }
        } else detail(e)
    }

    private fun detail(e: VaultEntry) = withAuth {
        val pw = store.password(e.id).orEmpty()
        val pwView = TextView(this).apply {
            text = "••••••••"
            textSize = 18f
            setTextIsSelectable(true)
            setOnClickListener { text = if (text == pw) "••••••••" else pw }
        }
        val p = (20 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p / 2, p, 0)
            addView(TextView(this@VaultActivity).apply { text = "ユーザー名"; textSize = 12f })
            addView(TextView(this@VaultActivity).apply { text = e.username; textSize = 18f; setTextIsSelectable(true) })
            addView(TextView(this@VaultActivity).apply { text = "パスワード (タップで表示)"; textSize = 12f; setPadding(0, p / 2, 0, 0) })
            addView(pwView)
        }
        AlertDialog.Builder(this).setTitle(e.title).setView(body)
            .setPositiveButton("パスワードをコピー") { _, _ -> copy(pw) }
            .setNeutralButton("編集") { _, _ -> edit(e) }
            .setNegativeButton("削除") { _, _ ->
                AlertDialog.Builder(this).setMessage("${e.title} (${e.username}) を削除しますか?")
                    .setPositiveButton("削除") { _, _ -> store.delete(e.id); reload() }
                    .setNegativeButton("キャンセル", null).show()
            }.show()
    }

    private fun copy(s: String) {
        val clip = ClipData.newPlainText("password", s)
        if (Build.VERSION.SDK_INT >= 24) {
            // Android 13+ でクリップボードのプレビューに出さない
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        toast("コピーしました")
    }

    private fun edit(e: VaultEntry?) = withAuth {
        val p = (20 * resources.displayMetrics.density).toInt()
        val site = EditText(this).apply { hint = "サイト (example.com) またはアプリ"; setText(e?.title.orEmpty()); isSingleLine = true }
        val user = EditText(this).apply { hint = "ユーザー名"; setText(e?.username.orEmpty()); isSingleLine = true }
        val pass = EditText(this).apply {
            hint = "パスワード"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            if (e != null) setText(store.password(e.id).orEmpty())
        }
        AlertDialog.Builder(this).setTitle(if (e == null) "パスワードを追加" else "編集")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0)
                addView(site); addView(user); addView(pass)
            })
            .setPositiveButton("保存") { _, _ ->
                val s = site.text.toString().trim()
                val isApp = e?.pkg?.isNotEmpty() == true && s == e.pkg
                withAuth {
                    if (e == null) store.upsert(if (isApp) "" else s, if (isApp) s else "", user.text.toString(), pass.text.toString())
                    else store.update(e.id, if (isApp) "" else s, if (isApp) s else "", user.text.toString(), pass.text.toString())
                    reload()
                }
            }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun pickCsv() {
        away = true
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_CSV)
    }

    @Deprecated("Activity Result API を使わない最小構成のため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        away = false
        val uri = data?.data
        if (requestCode != REQ_CSV || resultCode != RESULT_OK || uri == null) return
        val text = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
        val rows = text?.let { VaultCsv.parse(it) }.orEmpty()
        if (rows.isEmpty()) { toast("読み込める行がありませんでした"); return }
        withAuth {
            rows.forEach { store.upsert(VaultStore.normalizeDomain(it.url), "", it.username, it.password) }
            reload()
            toast("${rows.size} 件読み込みました")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PICK = "pick"
        const val EXTRA_PKG = "pkg"
        private const val REQ_CSV = 1
    }
}
