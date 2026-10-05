package io.github.nuko6925.flickkb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicInteger

data class UserWord(val id: Long, val reading: String, val surface: String)

/** ユーザ辞書 (よみ → 単語)。IME と設定画面は同じプロセスなので version で変更を伝える */
class UserDict private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "user.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE words(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            reading TEXT NOT NULL, surface TEXT NOT NULL,
            UNIQUE(reading, surface))""")
        db.execSQL("CREATE INDEX words_reading ON words(reading)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun all(): List<UserWord> = readableDatabase.rawQuery(
        "SELECT id, reading, surface FROM words ORDER BY reading, surface", null
    ).use { c -> buildList { while (c.moveToNext()) add(UserWord(c.getLong(0), c.getString(1), c.getString(2))) } }

    /** 読みの完全一致 */
    fun lookup(reading: String): List<String> = readableDatabase.rawQuery(
        "SELECT surface FROM words WHERE reading=? ORDER BY id", arrayOf(reading)
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** 複数の読みをまとめて (Lattice 用) */
    fun lookupMany(readings: Collection<String>): Map<String, List<String>> {
        if (readings.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<String>>()
        for (chunk in readings.chunked(400)) {
            readableDatabase.rawQuery(
                "SELECT reading, surface FROM words WHERE reading IN (" + chunk.joinToString(",") { "?" } + ")",
                chunk.toTypedArray()
            ).use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() }.add(c.getString(1)) }
        }
        return out
    }

    /** 読みが prefix で始まる (prefix 自身は除く) 語。(読み, 単語) */
    fun predict(prefix: String): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT reading, surface FROM words WHERE reading>? AND reading<? ORDER BY length(reading), id LIMIT 5",
        arrayOf(prefix, prefix + '\uFFFF')
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    /** @return false: 同じ組み合わせが既にある */
    fun add(reading: String, surface: String): Boolean {
        val ok = writableDatabase.insertWithOnConflict("words", null, android.content.ContentValues().apply {
            put("reading", normalize(reading)); put("surface", surface.trim())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (ok) version.incrementAndGet()
        return ok
    }

    fun update(id: Long, reading: String, surface: String): Boolean {
        val ok = runCatching {
            writableDatabase.update("words", android.content.ContentValues().apply {
                put("reading", normalize(reading)); put("surface", surface.trim())
            }, "id=?", arrayOf(id.toString())) > 0
        }.getOrDefault(false)
        if (ok) version.incrementAndGet()
        return ok
    }

    fun delete(id: Long) {
        writableDatabase.delete("words", "id=?", arrayOf(id.toString()))
        version.incrementAndGet()
    }

    companion object {
        /** 変更のたびに増える。変換側のキャッシュ破棄に使う */
        val version = AtomicInteger(0)

        @Volatile private var instance: UserDict? = null

        fun get(ctx: Context): UserDict = instance ?: synchronized(this) {
            instance ?: UserDict(ctx.applicationContext).also { instance = it }
        }

        /** よみ: 前後の空白を除き、カタカナはひらがなに、英字は小文字に */
        fun normalize(r: String) = buildString {
            for (c in r.trim()) append(when (c) {
                in 'ァ'..'ヶ' -> c - 0x60
                in 'A'..'Z' -> c.lowercaseChar()
                else -> c
            })
        }

        fun isValidReading(r: String) = normalize(r).let { n ->
            n.isNotEmpty() && n.all { Converter.isHiragana(it) || it in 'a'..'z' || it in '0'..'9' || it in "-_'" }
        }
    }
}
