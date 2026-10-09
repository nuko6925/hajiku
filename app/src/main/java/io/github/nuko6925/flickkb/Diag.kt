package io.github.nuko6925.flickkb

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自動入力まわりの診断ログ (アプリ内「診断ログ」と adb logcat -s Hajiku で見られる)。
 * パスワードやユーザー名は書かない
 */
object Diag {
    private const val TAG = "Hajiku"
    private const val MAX = 200
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "diag.log")
    }

    @Synchronized
    fun log(msg: String) {
        Log.d(TAG, msg)
        val f = file ?: return
        runCatching {
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "${fmt.format(Date())} $msg"
            f.writeText(lines.takeLast(MAX).joinToString("\n") + "\n")
        }
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText().orEmpty().ifEmpty { "(ログなし)" }

    fun clear() { file?.delete() }
}
