package io.github.nuko6925.flickkb

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast

/**
 * 自動入力から呼ばれる透明な画面: 生体認証してから
 * - fill: パスワードを復号して入力値を返す (前回使用日時を更新)
 * - save: 暗号化して保存
 */
class VaultAuthActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE)
        Diag.init(this)
        Diag.log("auth: 起動 mode=$mode")
        VaultAuth.authenticate(this, if (mode == MODE_SAVE) "パスワードを保存" else "パスワードを入力",
            onOk = { when (mode) { MODE_SAVE -> save(); MODE_PICK -> pick(); else -> fill() } },
            onFail = { msg ->
                msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                setResult(RESULT_CANCELED); finish()
            })
    }

    /** その他のパスワード: 一覧 (このサイトのものが先頭) から選ぶ */
    private fun pick() {
        val store = VaultStore.get(this)
        val matching = store.matching(intent.getStringExtra(EXTRA_DOMAIN), intent.getStringExtra(EXTRA_PKG))
        val list = matching + store.all().filter { e -> matching.none { it.id == e.id } }
        if (list.isEmpty()) {
            Toast.makeText(this, "保存されたパスワードはありません", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED); finish(); return
        }
        showSheet(matching, store.all().filter { e -> matching.none { it.id == e.id } })
    }

    /** iOS 風のシート: 画面下から 9 割の高さ。外側タップ / × で閉じる */
    private fun showSheet(matching: List<VaultEntry>, others: List<VaultEntry>) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val cancel = { setResult(RESULT_CANCELED); finish() }
        val site = intent.getStringExtra(EXTRA_DOMAIN)
        val sheet = VaultPickerView(this, site, matching, others,
            onPick = { e -> fill(e.id) },
            onInfo = { e -> info(e) },
            onClose = cancel,
            onAdd = {
                // 追加は保管庫画面で (戻ってきたら選び直し)
                startActivity(android.content.Intent(this, VaultActivity::class.java))
                cancel()
            })
        val h = (resources.displayMetrics.heightPixels * 0.9f).toInt()
        setContentView(android.widget.FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { cancel() }
            addView(sheet, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, h, android.view.Gravity.BOTTOM).apply {
                topMargin = 0
            })
            sheet.isClickable = true  // シート内のタップで閉じない
            // ナビゲーションバーの分だけ検索欄を上げる
            setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION") sheet.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
                insets
            }
        })
        sheet.translationY = h.toFloat()
        sheet.animate().translationY(0f).setDuration(260).start()
    }

    /** ⓘ: ユーザー名とパスワードの表示・コピー */
    private fun info(e: VaultEntry) {
        val pw = runCatching { VaultStore.get(this).password(e.id) }.getOrNull().orEmpty()
        val shown = booleanArrayOf(false)
        val pwView = android.widget.TextView(this).apply {
            text = "••••••••"; textSize = 18f
            setOnClickListener { shown[0] = !shown[0]; text = if (shown[0]) pw else "••••••••" }
        }
        val p = (20 * resources.displayMetrics.density).toInt()
        android.app.AlertDialog.Builder(this).setTitle(e.title)
            .setView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0)
                addView(android.widget.TextView(this@VaultAuthActivity).apply { text = "ユーザー名"; textSize = 12f })
                addView(android.widget.TextView(this@VaultAuthActivity).apply { text = e.username; textSize = 18f; setTextIsSelectable(true) })
                addView(android.widget.TextView(this@VaultAuthActivity).apply { text = "パスワード (タップで表示)"; textSize = 12f; setPadding(0, p / 2, 0, 0) })
                addView(pwView)
            })
            .setPositiveButton("入力") { _, _ -> fill(e.id) }
            .setNeutralButton("パスワードをコピー") { _, _ ->
                val clip = android.content.ClipData.newPlainText("password", pw)
                clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
                Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("閉じる", null).show()
    }

    private fun fill(id: Long = intent.getLongExtra(EXTRA_ID, -1)) {
        val store = VaultStore.get(this)
        val e = store.all().firstOrNull { it.id == id }
        val pw = runCatching { store.password(id) }.getOrNull()
        if (e == null || pw == null) { setResult(RESULT_CANCELED); finish(); return }
        store.touch(id)
        val rv = RemoteViews(packageName, android.R.layout.simple_list_item_1)
            .apply { setTextViewText(android.R.id.text1, e.username) }
        val ds = Dataset.Builder(rv)
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<AutofillId>(EXTRA_USER_ID)?.let { ds.setValue(it, AutofillValue.forText(e.username)) }
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<AutofillId>(EXTRA_PASS_ID)?.let { ds.setValue(it, AutofillValue.forText(pw)) }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds.build()))
        finish()
    }

    private fun save() {
        val ok = runCatching {
            VaultStore.get(this).upsert(
                intent.getStringExtra(EXTRA_DOMAIN).orEmpty(), intent.getStringExtra(EXTRA_PKG).orEmpty(),
                intent.getStringExtra(EXTRA_USER).orEmpty(), intent.getStringExtra(EXTRA_PASS).orEmpty())
        }.isSuccess
        Toast.makeText(this, if (ok) "Hajiku に保存しました" else "保存できませんでした", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val MODE_FILL = "fill"
        private const val MODE_SAVE = "save"
        private const val MODE_PICK = "pick"
        private const val EXTRA_ID = "id"
        private const val EXTRA_USER_ID = "user_id"
        private const val EXTRA_PASS_ID = "pass_id"
        private const val EXTRA_DOMAIN = "domain"
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_USER = "user"
        private const val EXTRA_PASS = "pass"

        private var reqCode = 1

        fun fillIntent(ctx: Context, id: Long, user: AutofillId?, pass: AutofillId?): PendingIntent {
            val i = Intent(ctx, VaultAuthActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_FILL).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_USER_ID, user).putExtra(EXTRA_PASS_ID, pass)
            // FLAG_MUTABLE: システムが認証結果を受け取るために extras を足す
            return PendingIntent.getActivity(ctx, reqCode++, i,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        }

        fun pickIntent(ctx: Context, user: AutofillId?, pass: AutofillId?, domain: String?, pkg: String): PendingIntent {
            val i = Intent(ctx, VaultAuthActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_PICK).putExtra(EXTRA_USER_ID, user).putExtra(EXTRA_PASS_ID, pass)
                .putExtra(EXTRA_DOMAIN, domain).putExtra(EXTRA_PKG, pkg)
            return PendingIntent.getActivity(ctx, reqCode++, i,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        }

        fun saveIntent(ctx: Context, domain: String, pkg: String, user: String, pass: String): PendingIntent {
            val i = Intent(ctx, VaultAuthActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_SAVE).putExtra(EXTRA_DOMAIN, domain).putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_USER, user).putExtra(EXTRA_PASS, pass)
            return PendingIntent.getActivity(ctx, reqCode++, i,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
