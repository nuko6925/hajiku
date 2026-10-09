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
        VaultAuth.authenticate(this, if (mode == MODE_SAVE) "パスワードを保存" else "パスワードを入力",
            onOk = { if (mode == MODE_SAVE) save() else fill() },
            onFail = { msg ->
                msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                setResult(RESULT_CANCELED); finish()
            })
    }

    private fun fill() {
        val id = intent.getLongExtra(EXTRA_ID, -1)
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

        fun saveIntent(ctx: Context, domain: String, pkg: String, user: String, pass: String): PendingIntent {
            val i = Intent(ctx, VaultAuthActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_SAVE).putExtra(EXTRA_DOMAIN, domain).putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_USER, user).putExtra(EXTRA_PASS, pass)
            return PendingIntent.getActivity(ctx, reqCode++, i,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
