package io.github.nuko6925.flickkb

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
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
        )
    }
}

class HajikuAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val f = LoginParser.parse(structure)
        val ids = listOfNotNull(f.username, f.password)
        if (ids.isEmpty() || f.pkg == packageName) return callback.onSuccess(null)

        val entries = VaultStore.get(this).matching(f.domain, f.pkg)
        val resp = FillResponse.Builder()
        val specs = if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty() else emptyList()
        val maxInline = if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.maxSuggestionCount ?: 0 else 0

        // 前回使ったアカウントが先頭 (VaultStore が last_used 順で返す)
        entries.take(MAX_DATASETS).forEachIndexed { i, e ->
            val auth = VaultAuthActivity.fillIntent(this, e.id, f.username, f.password)
            val label = e.username.ifEmpty { "(ユーザー名なし)" }
            val rv = remote(label, e.title)
            val inline = if (Build.VERSION.SDK_INT >= 30 && i < maxInline && specs.isNotEmpty())
                inline(specs[minOf(i, specs.size - 1)], label, null) else null
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

        // 保存: ログインしたら「Hajiku に保存しますか?」
        if (f.password != null) {
            val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (f.username != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
            resp.setSaveInfo(SaveInfo.Builder(type, ids.toTypedArray()).build())
        }
        if (entries.isEmpty() && f.password == null) return callback.onSuccess(null)
        callback.onSuccess(runCatching { resp.build() }.getOrNull())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        val f = LoginParser.parse(structure)
        val pw = f.passwordValue
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
        if (!UiVersions.getVersions(style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val attribution = PendingIntent.getActivity(this, 0, Intent(this, VaultActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val content = InlineSuggestionUi.newContentBuilder(attribution).setTitle(title)
            .apply { if (sub != null) setSubtitle(sub) }
            .setContentDescription(title).build()
        return InlinePresentation(content.slice, spec, false)
    }

    companion object {
        private const val MAX_DATASETS = 8

        fun isSelected(ctx: Context) =
            android.provider.Settings.Secure.getString(ctx.contentResolver, "autofill_service")
                ?.startsWith(ctx.packageName + "/") == true
    }
}
