package io.github.nuko6925.flickkb

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.security.keystore.UserNotAuthenticatedException
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保管庫 (Hajiku アプリの「パスワード」)。中身は自動入力のシートと同じ VaultSheetView。
 * pick = true (キーボードの 🔑 から開けた場合): 選んだアカウントをキーボードが入力する
 */
class VaultActivity : Activity() {
    private var pick = false
    private var targetPkg: String? = null
    private var unlocked = false
    /** 認証画面 (PIN 入力は別画面) やファイル選択で一時的に離れている */
    private var away = false
    private var sheet: VaultSheetView? = null
    private val store by lazy { VaultStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastCreated = System.currentTimeMillis()
        // スクショ・最近使ったアプリのサムネイルに写さない
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Diag.init(this)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        targetPkg = intent.getStringExtra(EXTRA_PKG)
        Diag.log("vault: 起動 pick=$pick")
        title = "パスワード"
        setContentView(FrameLayout(this).apply { setBackgroundColor(SheetTheme(this@VaultActivity).sheet) })
    }

    override fun onStart() {
        super.onStart()
        if (!unlocked) unlock()
    }

    override fun onStop() {
        super.onStop()
        if (away) return
        // 離れたら鍵をかけて中身を消す。選択モードは閉じる
        unlocked = false
        sheet = null
        setContentView(FrameLayout(this).apply { setBackgroundColor(SheetTheme(this@VaultActivity).sheet) })
        if (pick) finish()
    }

    private fun unlock() {
        away = true
        VaultAuth.authenticate(this, "パスワードを表示",
            onOk = { away = false; unlocked = true; show() },
            onFail = { msg -> away = false; Diag.log("vault: 認証失敗 $msg"); msg?.let { toast(it) }; finish() })
    }

    private fun show() {
        val s = VaultSheetView(this, pick = pick, site = null, domain = null, pkg = targetPkg,
            host = object : VaultSheetView.Host {
                override fun close() = finish()
                override fun fill(e: VaultEntry) = withAuth {
                    val pw = store.password(e.id) ?: return@withAuth
                    store.touch(e.id)
                    PendingFill.set(e.username, pw)
                    finish()
                }
                override fun withAuth(block: () -> Unit) = this@VaultActivity.withAuth(block = block)
                override fun importFile() = pickFile()
                override fun exportFile() = chooseExport()
            })
        sheet = s
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(SheetTheme(this@VaultActivity).sheet)
            addView(s, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION") s.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        })
    }

    @Deprecated("最小構成のため")
    override fun onBackPressed() {
        if (sheet?.back() == true) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** 認証が切れていたら (60 秒経過) もう一度認証してから実行 */
    private fun withAuth(onCancel: () -> Unit = {}, block: () -> Unit) {
        try {
            block()
        } catch (e: UserNotAuthenticatedException) {
            away = true
            VaultAuth.authenticate(this, "パスワードを表示",
                onOk = { away = false; runCatching(block).onFailure { toast("失敗しました"); onCancel() } },
                onFail = { msg -> away = false; msg?.let { toast(it) }; onCancel() })
        } catch (e: Exception) {
            toast("失敗しました: ${e.message}")
            onCancel()
        }
    }

    // ---- 書き出し / 読み込み ----

    /** 書き出しのパスフレーズ (ファイルの保存先を選んでいる間だけ持つ) */
    private var pendingPass: CharArray? = null

    private fun chooseExport() {
        if (store.all().isEmpty()) { toast("書き出すパスワードがありません"); return }
        val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        AlertDialog.Builder(this)
            .setTitle("パスワードを書き出す")
            .setItems(arrayOf("暗号化して書き出す (Hajiku 用)", "CSV で書き出す (暗号化なし)")) { _, i ->
                if (i == 0) askPassphrase(confirm = true) { p ->
                    pendingPass = p
                    createDocument(REQ_EXPORT_VAULT, "application/octet-stream", "hajiku-$date.${VaultBackup.EXTENSION}")
                } else AlertDialog.Builder(this)
                    .setTitle("CSV で書き出しますか?")
                    .setMessage("CSV ファイルのパスワードは暗号化されず、ファイルを開ければ誰でも読めます。" +
                        "他のパスワード管理アプリに読み込んだら、すぐに削除してください。\n\n" +
                        "機種変更で Hajiku に移すだけなら「暗号化して書き出す」を使ってください。")
                    .setPositiveButton("書き出す") { _, _ -> createDocument(REQ_EXPORT_CSV, "text/csv", "hajiku-$date.csv") }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** confirm = true: 書き出し用 (2 回入力、8 文字以上) */
    private fun askPassphrase(confirm: Boolean, message: String? = null, onOk: (CharArray) -> Unit) {
        val dp = resources.displayMetrics.density
        fun field(h: String) = EditText(this).apply {
            hint = h; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val p1 = field("パスフレーズ")
        val p2 = field("もう一度入力")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (8 * dp).toInt(), (22 * dp).toInt(), 0)
            addView(p1)
            if (confirm) addView(p2)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(if (confirm) "パスフレーズを設定" else "パスフレーズを入力")
            .setMessage(message ?: if (confirm) "読み込むときに必要です。忘れると復元できません。" else "書き出したときに設定したパスフレーズ")
            .setView(box)
            .setPositiveButton("OK", null)
            .setNegativeButton("キャンセル", null)
            .create()
        d.setOnShowListener {
            d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val a = p1.text.toString()
                when {
                    a.isEmpty() -> {}
                    confirm && a.length < 8 -> toast("8 文字以上にしてください")
                    confirm && a != p2.text.toString() -> toast("パスフレーズが一致しません")
                    else -> {
                        p1.text.clear(); p2.text.clear()
                        d.dismiss()
                        onOk(a.toCharArray())
                    }
                }
            }
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        p1.requestFocus()
    }

    private fun createDocument(req: Int, mime: String, name: String) {
        away = true
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }, req)
    }

    private fun pickFile() {
        away = true
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_IMPORT)
    }

    @Deprecated("Activity Result API を使わない最小構成のため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        away = false
        val pass = pendingPass
        pendingPass = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { pass?.fill('\u0000'); return }
        when (requestCode) {
            REQ_IMPORT -> importFrom(uri)
            REQ_EXPORT_VAULT -> if (pass != null) exportTo(uri, pass)
            REQ_EXPORT_CSV -> exportTo(uri, null)
        }
    }

    /** pass == null なら CSV */
    private fun exportTo(uri: Uri, pass: CharArray?) {
        // 認証をやめた場合は、保存先に作られた空のファイルを消す
        withAuth(onCancel = { pass?.fill('\u0000'); deleteQuietly(uri) }) {
            // 認証が切れていればここで例外になり、認証してからやり直す
            VaultCrypto.encrypt("")
            work(if (pass != null) "暗号化しています…" else "書き出しています…",
                job = {
                    try {
                        val entries = store.exportAll()
                        val text = if (pass != null) VaultBackup.encode(entries, pass) else VaultCsv.write(entries)
                        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        entries.size
                    } finally {
                        pass?.fill('\u0000')
                    }
                },
                done = { n -> toast("$n 件書き出しました") },
                fail = { e ->
                    Diag.log("vault: 書き出し失敗 $e")
                    deleteQuietly(uri)
                    toast("書き出せませんでした: ${e.message}")
                })
        }
    }

    private fun deleteQuietly(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    private fun importFrom(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri)!!.use { readLimited(it) } }
            .getOrElse { e -> toast("ファイルを読めませんでした: ${e.message}"); return }
        if (VaultBackup.looksLike(text)) {
            importBackup(text, null)
            return
        }
        val rows = VaultCsv.parse(text).map { VaultCsv.toEntry(it) }
        if (rows.isEmpty()) { toast("読み込める行がありませんでした"); return }
        restore(rows, keepNewer = false)
    }

    private fun importBackup(text: String, message: String?) {
        askPassphrase(confirm = false, message = message) { p ->
            work("復号しています…",
                job = { try { VaultBackup.decode(text, p) } finally { p.fill('\u0000') } },
                done = { list ->
                    if (list.isEmpty()) toast("パスワードが入っていませんでした") else restore(list, keepNewer = true)
                },
                fail = { e ->
                    if (e is VaultBackup.WrongPassphrase) importBackup(text, "パスフレーズが違います。もう一度入力してください")
                    else toast("読み込めませんでした: ${e.message}")
                })
        }
    }

    private fun restore(list: List<PlainEntry>, keepNewer: Boolean) = withAuth {
        VaultCrypto.encrypt("")
        work("読み込んでいます…",
            job = { store.restore(list, keepNewer) },
            done = { r ->
                sheet?.reload()
                toast(buildString {
                    append("${r.added} 件追加")
                    if (r.updated > 0) append("、${r.updated} 件更新")
                    if (r.skipped > 0) append("、${r.skipped} 件はそのまま")
                })
            },
            fail = { e ->
                Diag.log("vault: 読み込み失敗 $e")
                toast(if (e is UserNotAuthenticatedException) "時間がかかりすぎました。もう一度読み込んでください"
                      else "読み込めませんでした: ${e.message}")
            })
    }

    /** 重い処理を別スレッドで。終わるまで操作できないようにする */
    private fun <T> work(message: String, job: () -> T, done: (T) -> Unit, fail: (Throwable) -> Unit) {
        val dlg = AlertDialog.Builder(this).setMessage(message).setCancelable(false).show()
        Thread {
            val r = runCatching(job)
            runOnUiThread {
                if (!isDestroyed) runCatching { dlg.dismiss() }
                r.fold(done, fail)
            }
        }.start()
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_IMPORT_BYTES) throw IllegalArgumentException("ファイルが大きすぎます")
        }
        return out.toString("UTF-8")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** キーボードから起動できたかの確認用 */
        @Volatile var lastCreated = 0L
        const val EXTRA_PICK = "pick"
        const val EXTRA_PKG = "pkg"
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT_VAULT = 2
        private const val REQ_EXPORT_CSV = 3
        private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }
}
