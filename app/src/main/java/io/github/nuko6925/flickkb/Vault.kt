package io.github.nuko6925.flickkb

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * パスワード保管庫。
 * - パスワードは Android Keystore の AES-GCM 鍵で暗号化。鍵は「直近 60 秒以内に生体認証か端末ロック解除」が使用条件
 * - サイト・ユーザー名・最終使用日時は平文 (iOS と同じく、認証前の候補表示に使う)
 * - INTERNET 権限は無いので端末の外には出ない。自動バックアップ対象外 (allowBackup=false)
 */
data class VaultEntry(
    val id: Long,
    /** Web サイトのドメイン (example.com)。アプリの場合は空 */
    val domain: String,
    /** アプリのパッケージ名。Web サイトの場合は空 */
    val pkg: String,
    val username: String,
    val lastUsed: Long,
) {
    val title get() = domain.ifEmpty { pkg }
}

object VaultCrypto {
    private const val ALIAS = "hajiku_vault"
    const val AUTH_SECONDS = 60

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(AUTH_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    /** @throws UserNotAuthenticatedException 認証から時間が経っている */
    fun encrypt(plain: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(blob: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
    }
}

class VaultStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "vault.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entries(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            domain TEXT NOT NULL DEFAULT '', pkg TEXT NOT NULL DEFAULT '',
            username TEXT NOT NULL, secret BLOB NOT NULL,
            created INTEGER NOT NULL, last_used INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX entries_domain ON entries(domain)")
        db.execSQL("CREATE INDEX entries_pkg ON entries(pkg)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    private fun rows(where: String?, args: Array<String>?): List<VaultEntry> = readableDatabase.query(
        "entries", arrayOf("id", "domain", "pkg", "username", "last_used"), where, args, null, null,
        "last_used DESC, domain, username"
    ).use { c ->
        buildList { while (c.moveToNext()) add(VaultEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))) }
    }

    fun all() = rows(null, null)

    /** 候補: Web サイトはドメイン (サブドメイン含む)、アプリはパッケージで一致。前回使ったものが先頭 */
    fun matching(domain: String?, pkg: String?): List<VaultEntry> {
        val d = domain?.let { normalizeDomain(it) }.orEmpty()
        if (d.isNotEmpty()) {
            return all().filter { it.domain.isNotEmpty() && (d == it.domain || d.endsWith("." + it.domain) || it.domain.endsWith(".$d")) }
        }
        if (!pkg.isNullOrEmpty()) return rows("pkg=?", arrayOf(pkg))
        return emptyList()
    }

    /** 要認証 (60 秒以内) */
    fun password(id: Long): String? = readableDatabase.query("entries", arrayOf("secret"), "id=?",
        arrayOf(id.toString()), null, null, null).use { c ->
        if (c.moveToNext()) VaultCrypto.decrypt(c.getBlob(0)) else null
    }

    /** 追加。同じサイト+ユーザー名があればパスワードを更新。要認証 */
    fun upsert(domain: String, pkg: String, username: String, password: String): Long {
        val d = normalizeDomain(domain)
        val secret = VaultCrypto.encrypt(password)
        val db = writableDatabase
        val existing = db.query("entries", arrayOf("id"), "domain=? AND pkg=? AND username=?",
            arrayOf(d, pkg, username), null, null, null).use { if (it.moveToNext()) it.getLong(0) else null }
        val cv = ContentValues().apply {
            put("domain", d); put("pkg", pkg); put("username", username); put("secret", secret)
        }
        return if (existing != null) {
            db.update("entries", cv, "id=?", arrayOf(existing.toString())); existing
        } else {
            cv.put("created", System.currentTimeMillis())
            db.insert("entries", null, cv)
        }
    }

    fun update(id: Long, domain: String, pkg: String, username: String, password: String) {
        writableDatabase.update("entries", ContentValues().apply {
            put("domain", normalizeDomain(domain)); put("pkg", pkg); put("username", username)
            put("secret", VaultCrypto.encrypt(password))
        }, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) { writableDatabase.delete("entries", "id=?", arrayOf(id.toString())) }

    fun touch(id: Long) {
        writableDatabase.update("entries", ContentValues().apply { put("last_used", System.currentTimeMillis()) },
            "id=?", arrayOf(id.toString()))
    }

    companion object {
        @Volatile private var instance: VaultStore? = null
        fun get(ctx: Context): VaultStore = instance ?: synchronized(this) {
            instance ?: VaultStore(ctx.applicationContext).also { instance = it }
        }

        /** URL やホスト名 → 比較用のドメイン (小文字、www. 無し) */
        fun normalizeDomain(s: String): String {
            if (s.isBlank()) return ""
            val host = if ("://" in s) Uri.parse(s.trim()).host ?: s else s.substringBefore('/')
            return host.trim().lowercase().removePrefix("www.").substringBefore(':')
        }
    }
}

/** 生体認証 (失敗・非対応時は端末のパスコード) */
object VaultAuth {
    /** 端末にロック (PIN 等) が設定されていないと保管庫は使えない */
    fun canAuthenticate(ctx: Context): Boolean {
        val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
        return km?.isDeviceSecure == true
    }

    fun authenticate(activity: Activity, title: String, onOk: () -> Unit, onFail: (String?) -> Unit) {
        if (!canAuthenticate(activity)) {
            onFail("端末の画面ロック (PIN・パスワード等) を設定してください")
            return
        }
        val b = BiometricPrompt.Builder(activity).setTitle(title)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        }
        // 端末によって例外になる組み合わせがあるので、落とさずにメッセージにする
        try {
            b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                override fun onAuthenticationError(code: Int, msg: CharSequence) = onFail(msg.toString())
            })
        } catch (e: Exception) {
            onFail("認証を開始できませんでした: ${e.message}")
        }
    }
}

/** 🔑 から選んだアカウントを IME に渡す (同一プロセス内) */
object PendingFill {
    private var username: String? = null
    private var password: String? = null
    private var at = 0L

    fun set(u: String, p: String) { username = u; password = p; at = System.currentTimeMillis() }

    /** 1 分以内のものだけ。取り出したら消す */
    fun take(): Pair<String, String>? {
        val u = username; val p = password
        clear()
        if (u == null || p == null || System.currentTimeMillis() - at > 60_000) return null
        return u to p
    }

    fun takePasswordOnly(): String? = take()?.second

    fun clear() { username = null; password = null }
}

/** CSV (iOS「パスワード」/ Safari、Chrome / Google パスワードマネージャー の書き出し形式) */
object VaultCsv {
    class Row(val url: String, val username: String, val password: String)

    fun parse(text: String): List<Row> {
        val recs = records(text.removePrefix("﻿"))
        if (recs.isEmpty()) return emptyList()
        val head = recs[0].map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> head.indexOf(n).takeIf { it >= 0 } } ?: -1
        val url = col("url", "website", "origin")
        val user = col("username", "login", "user")
        val pass = col("password")
        val title = col("title", "name")
        if (pass < 0) return emptyList()
        return recs.drop(1).mapNotNull { r ->
            val p = r.getOrNull(pass).orEmpty()
            if (p.isEmpty()) return@mapNotNull null
            val u = r.getOrNull(url).orEmpty().ifEmpty { r.getOrNull(title).orEmpty() }
            Row(u, r.getOrNull(user).orEmpty(), p)
        }
    }

    /** RFC 4180 程度: "" のエスケープ、引用符内の改行 */
    private fun records(s: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else q = false
                } else f.append(c)
            } else when (c) {
                '"' -> q = true
                ',' -> { row.add(f.toString()); f.clear() }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.clear(); out.add(row); row = ArrayList() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); out.add(row) }
        return out.filter { r -> r.any { it.isNotEmpty() } }
    }
}
