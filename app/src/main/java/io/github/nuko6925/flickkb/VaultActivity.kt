package io.github.nuko6925.flickkb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.security.keystore.UserNotAuthenticatedException
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

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
                override fun withAuth(block: () -> Unit) = this@VaultActivity.withAuth(block)
                override fun importCsv() = pickCsv()
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

    private fun pickCsv() {
        away = true
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_CSV)
    }

    @Deprecated("Activity Result API を使わない最小構成のため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        away = false
        val uri = data?.data
        if (requestCode != REQ_CSV || resultCode != RESULT_OK || uri == null) return
        val text = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
        val rows = text?.let { VaultCsv.parse(it) }.orEmpty()
        if (rows.isEmpty()) { toast("読み込める行がありませんでした"); return }
        withAuth {
            rows.forEach { store.upsert(VaultStore.normalizeDomain(it.url), "", it.username, it.password) }
            sheet?.reload()
            toast("${rows.size} 件読み込みました")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** キーボードから起動できたかの確認用 */
        @Volatile var lastCreated = 0L
        const val EXTRA_PICK = "pick"
        const val EXTRA_PKG = "pkg"
        private const val REQ_CSV = 1
    }
}
