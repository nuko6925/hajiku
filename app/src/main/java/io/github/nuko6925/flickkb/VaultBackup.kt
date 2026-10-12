package io.github.nuko6925.flickkb

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.CharBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 書き出し・読み込み用の平文の 1 件 */
class PlainEntry(
    val domain: String,
    val pkg: String,
    val username: String,
    val password: String,
    val created: Long = 0,
    val lastUsed: Long = 0,
    val modified: Long = 0,
)

/**
 * 暗号化バックアップ (.hajikuvault)。機種変更・バックアップ用。
 *
 * Keystore の鍵は端末から取り出せないので、パスフレーズから作った鍵で暗号化し直して書き出す。
 * ファイルは UTF-8 の JSON:
 *
 *     {"format":"hajiku-vault","version":1,
 *      "kdf":"PBKDF2-HMAC-SHA256","iterations":600000,
 *      "salt":"<base64 16 バイト>","iv":"<base64 12 バイト>",
 *      "data":"<base64 AES-256-GCM の暗号文 + 16 バイトのタグ>"}
 *
 * - 鍵 = PBKDF2-HMAC-SHA256(パスフレーズの UTF-8, salt, iterations, 32 バイト)
 * - AAD = "hajiku-vault/1"
 * - 平文 = {"entries":[{"domain","pkg","username","password","created","lastUsed","modified"}, …]}
 *   (日時は Unix ミリ秒)
 *
 * 端末外でも tools/vault_decrypt.py で復号できる。
 */
object VaultBackup {
    const val FORMAT = "hajiku-vault"
    const val EXTENSION = "hajikuvault"
    private const val VERSION = 1
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val ITERATIONS = 600_000
    private val AAD = "$FORMAT/$VERSION".toByteArray(Charsets.UTF_8)

    class WrongPassphrase : Exception("パスフレーズが違います")

    /** Hajiku の暗号化バックアップらしいか (CSV と見分ける) */
    fun looksLike(text: String) = text.trimStart().startsWith("{") && "\"$FORMAT\"" in text

    /**
     * PBKDF2-HMAC-SHA256 で 32 バイト (1 ブロック)。
     * SecretKeyFactory は非 ASCII のパスフレーズの扱い (UTF-8 か 8bit か) が実装によって違うので、
     * UTF-8 と決めて自前で計算する
     */
    private fun derive(pass: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(pass))
        val pw = ByteArray(bb.remaining()).also { bb.get(it) }
        bb.array().fill(0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pw, "HmacSHA256"))
        pw.fill(0)
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return SecretKeySpec(t, "AES").also { t.fill(0) }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** 重い (PBKDF2)。UI スレッドで呼ばないこと */
    fun encode(entries: List<PlainEntry>, pass: CharArray): String {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().apply {
            put("domain", e.domain); put("pkg", e.pkg); put("username", e.username); put("password", e.password)
            put("created", e.created); put("lastUsed", e.lastUsed); put("modified", e.modified)
        })
        val plain = JSONObject().put("entries", arr).toString().toByteArray(Charsets.UTF_8)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(pass, salt, ITERATIONS), GCMParameterSpec(128, iv))
        c.updateAAD(AAD)
        val data = c.doFinal(plain)
        plain.fill(0)
        return JSONObject().apply {
            put("format", FORMAT); put("version", VERSION)
            put("kdf", KDF); put("iterations", ITERATIONS)
            put("salt", b64(salt)); put("iv", b64(iv)); put("data", b64(data))
        }.toString(2) + "\n"
    }

    /**
     * 重い (PBKDF2)。UI スレッドで呼ばないこと
     * @throws WrongPassphrase パスフレーズが違う (またはファイルが壊れている)
     */
    fun decode(text: String, pass: CharArray): List<PlainEntry> {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "Hajiku のバックアップではありません" }
        require(o.optInt("version") == VERSION) { "対応していない版です (version ${o.opt("version")})。Hajiku を更新してください" }
        require(o.optString("kdf") == KDF) { "対応していない形式です" }
        val iterations = o.getInt("iterations")
        // 壊れた・悪意のあるファイルで固まらないように
        require(iterations in 10_000..10_000_000) { "ファイルが壊れています" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(pass, unb64(o.getString("salt")), iterations),
            GCMParameterSpec(128, unb64(o.getString("iv"))))
        c.updateAAD(AAD)
        val plain = try {
            c.doFinal(unb64(o.getString("data")))
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        }
        val arr = JSONObject(String(plain, Charsets.UTF_8)).getJSONArray("entries")
        plain.fill(0)
        return List(arr.length()) { i ->
            val e = arr.getJSONObject(i)
            PlainEntry(
                domain = e.optString("domain"), pkg = e.optString("pkg"),
                username = e.optString("username"), password = e.optString("password"),
                created = e.optLong("created"), lastUsed = e.optLong("lastUsed"), modified = e.optLong("modified"),
            )
        }
    }
}
