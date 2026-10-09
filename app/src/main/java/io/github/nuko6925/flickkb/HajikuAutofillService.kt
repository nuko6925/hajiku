package io.github.nuko6925.flickkb

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

/** 画面から見つけたログイン欄 */
class LoginFields(
    val username: AutofillId?,
    val password: AutofillId?,
    val domain: String?,
    val pkg: String,
    val usernameValue: String?,
    val passwordValue: String?,
    /** 信用しなかったものも含めた申告ドメイン (診断用) */
    val rawDomain: String? = null,
)

/** Web サイトの申告 (webDomain) を信用するブラウザ。それ以外のアプリはパッケージ名で照合 */
private val TRUSTED_BROWSERS = setOf(
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
    "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
    "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.vivaldi.browser",
    "com.opera.browser", "com.duckduckgo.mobile.android", "com.kiwibrowser.browser",
)

private fun trustWebDomain(pkg: String) = pkg in TRUSTED_BROWSERS || pkg.startsWith("io.github.nuko6925.")

object LoginParser {
    private val USER_WORDS = listOf("user", "mail", "login", "account", "id", "ユーザー", "メール", "アカウント")

    fun parse(structure: AssistStructure): LoginFields {
        val pkg = structure.activityComponent.packageName
        var user: AssistStructure.ViewNode? = null
        var pass: AssistStructure.ViewNode? = null
        var lastText: AssistStructure.ViewNode? = null
        var domain: String? = null

        fun isPassword(n: AssistStructure.ViewNode): Boolean {
            if (n.autofillHints?.any { it.contains("password", true) } == true) return true
            val t = n.inputType
            val v = t and InputType.TYPE_MASK_VARIATION
            if (t and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && v in setOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) return true
            return n.htmlInfo?.attributes?.any { it.first == "type" && it.second == "password" } == true
        }

        fun isUser(n: AssistStructure.ViewNode): Boolean {
            if (n.autofillHints?.any { h -> listOf("username", "email", "phone").any { h.contains(it, true) } } == true) return true
            val v = n.inputType and InputType.TYPE_MASK_VARIATION
            if (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return true
            val words = listOfNotNull(n.idEntry, n.hint, n.htmlInfo?.attributes?.firstOrNull { it.first == "name" }?.second,
                n.htmlInfo?.attributes?.firstOrNull { it.first == "autocomplete" }?.second)
            return words.any { w -> USER_WORDS.any { w.contains(it, true) } }
        }

        fun walk(n: AssistStructure.ViewNode) {
            n.webDomain?.takeIf { it.isNotEmpty() }?.let { if (domain == null) domain = it }
            val editable = n.autofillType == View.AUTOFILL_TYPE_TEXT && n.autofillId != null
            if (editable && n.visibility == View.VISIBLE) {
                when {
                    isPassword(n) -> if (pass == null) { pass = n; if (user == null) user = lastText }
                    pass == null && isUser(n) -> user = n
                    pass == null -> lastText = n
                }
            }
            for (i in 0 until n.childCount) walk(n.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)

        return LoginFields(
            user?.autofillId, pass?.autofillId,
            domain?.takeIf { trustWebDomain(pkg) }, pkg,
            user?.autofillValue?.takeIf { it.isText }?.textValue?.toString(),
            pass?.autofillValue?.takeIf { it.isText }?.textValue?.toString(),
            domain,
        )
    }
}

class HajikuAutofillService : AutofillService() {

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
    }

    override fun onConnected() { Diag.log("autofill: サービス接続") }

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val f = LoginParser.parse(structure)
        val ids = listOfNotNull(f.username, f.password)
        Diag.log("fill: pkg=${f.pkg} domain=${f.rawDomain}(信用=${f.domain != null}) user欄=${f.username != null} pass欄=${f.password != null}")
        if (ids.isEmpty() || f.pkg == packageName) return callback.onSuccess(null)
        LastLoginContext.set(f.pkg, f.domain)

        val entries = VaultStore.get(this).matching(f.domain, f.pkg)
        val resp = FillResponse.Builder()
        val specs = if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty() else emptyList()
        // 最後の 1 枠は 🔑 用に空けておく
        val maxInline = if (Build.VERSION.SDK_INT >= 30) ((request.inlineSuggestionsRequest?.maxSuggestionCount ?: 0) - 1).coerceAtLeast(0) else 0

        // 前回使ったアカウントが先頭 (VaultStore が last_used 順で返す)
        entries.take(MAX_DATASETS).forEachIndexed { i, e ->
            val auth = VaultAuthActivity.fillIntent(this, e.id, f.username, f.password)
            val label = e.username.ifEmpty { "(ユーザー名なし)" }
            val rv = remote(label, e.title)
            val inline = if (Build.VERSION.SDK_INT >= 30 && i < maxInline && specs.isNotEmpty())
                inline(specs.first(), label, null) else null
            val ds = Dataset.Builder(rv).setAuthentication(auth.intentSender)
            for (id in ids) {
                if (inline != null && Build.VERSION.SDK_INT >= 30) {
                    @Suppress("DEPRECATION") ds.setValue(id, null, rv, inline)
                } else {
                    @Suppress("DEPRECATION") ds.setValue(id, null, rv)
                }
            }
            resp.addDataset(ds.build())
        }

        // 「その他のパスワード」: 固定表示の候補 (キーボードは右端の 🔑 として表示)。
        // タップすると OS が認証画面を開く → 一覧から選ぶ → 入力。キーボードから直接画面は開けないためこの経路
        run {
            val pick = VaultAuthActivity.pickIntent(this, f.username, f.password, f.domain, f.pkg)
            val rv = remote("その他のパスワード…", "Hajiku")
            val pinned = if (Build.VERSION.SDK_INT >= 30 && specs.isNotEmpty())
                keyInline(specs.last()) else null
            val ds = Dataset.Builder(rv).setAuthentication(pick.intentSender)
            for (id in ids) {
                if (pinned != null && Build.VERSION.SDK_INT >= 30) {
                    @Suppress("DEPRECATION") ds.setValue(id, null, rv, pinned)
                } else {
                    @Suppress("DEPRECATION") ds.setValue(id, null, rv)
                }
            }
            resp.addDataset(ds.build())
        }

        // 保存: ログインしたら「Hajiku に保存しますか?」
        if (f.password != null) {
            val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (f.username != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
            resp.setSaveInfo(SaveInfo.Builder(type, ids.toTypedArray()).build())
        }
        Diag.log("fill: 一致=${entries.size} インライン枠=$maxInline spec=${specs.size}")
        callback.onSuccess(runCatching { resp.build() }.onFailure { Diag.log("fill: 応答作成失敗 $it") }.getOrNull())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        val f = LoginParser.parse(structure)
        val pw = f.passwordValue
        Diag.log("save: pkg=${f.pkg} domain=${f.domain} パスワード値=${!pw.isNullOrEmpty()}")
        if (pw.isNullOrEmpty()) return callback.onSuccess()
        // 暗号化には認証が必要なので、認証画面で保存する
        val i = VaultAuthActivity.saveIntent(this, f.domain.orEmpty(), if (f.domain == null) f.pkg else "", f.usernameValue.orEmpty(), pw)
        callback.onSuccess(i.intentSender)
    }

    private fun remote(title: String, sub: String) =
        RemoteViews(packageName, android.R.layout.simple_list_item_2).apply {
            setTextViewText(android.R.id.text1, title)
            setTextViewText(android.R.id.text2, sub)
        }

    private fun inline(spec: InlinePresentationSpec, title: String, sub: String?): InlinePresentation? {
        if (Build.VERSION.SDK_INT < 30) return null
        val style = spec.style
        if (!UiVersions.getVersions(style).contains(UiVersions.INLINE_UI_VERSION_1)) {
            Diag.log("fill: IME のインライン様式が非対応 ${UiVersions.getVersions(style)}")
            return null
        }
        val attribution = PendingIntent.getActivity(this, 0, Intent(this, VaultActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val content = InlineSuggestionUi.newContentBuilder(attribution).setTitle(title)
            .apply { if (sub != null) setSubtitle(sub) }
            .setContentDescription(title).build()
        return InlinePresentation(content.slice, spec, false)
    }

    /** 🔑 だけの固定表示チップ */
    private fun keyInline(spec: InlinePresentationSpec): InlinePresentation? {
        if (Build.VERSION.SDK_INT < 30) return null
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val res = resources.getIdentifier("ic_key", "drawable", packageName)
        val attribution = PendingIntent.getActivity(this, 1, Intent(this, VaultActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val content = InlineSuggestionUi.newContentBuilder(attribution)
            // キーボードの文字色に合わせる (ライト: 黒 / ダーク: 白)
            .setStartIcon(Icon.createWithResource(packageName, res).setTint(
                if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()))
            .setContentDescription("その他のパスワード").build()
        return InlinePresentation(content.slice, spec, true)
    }

    companion object {
        private const val MAX_DATASETS = 8

        fun isSelected(ctx: Context) =
            android.provider.Settings.Secure.getString(ctx.contentResolver, "autofill_service")
                ?.startsWith(ctx.packageName + "/") == true
    }
}
