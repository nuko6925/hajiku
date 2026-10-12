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

    class ImportResult(val added: Int, val duplicate: Int, val invalid: Int)

    /** まとめて追加 (読み込み用)。同じ よみ+単語 は飛ばす。よみが不正な行は数えて飛ばす */
    fun addAll(words: List<Pair<String, String>>): ImportResult {
        var added = 0; var dup = 0; var invalid = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((r, s) in words) {
                val surface = s.trim()
                if (surface.isEmpty() || !isValidReading(r)) { invalid++; continue }
                val ok = db.insertWithOnConflict("words", null, android.content.ContentValues().apply {
                    put("reading", normalize(r)); put("surface", surface)
                }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                if (ok) added++ else dup++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) version.incrementAndGet()
        return ImportResult(added, dup, invalid)
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

/**
 * ユーザ辞書のファイル。
 * 書き出し: Google 日本語入力 / Mozc と同じ「よみ<TAB>単語<TAB>品詞<TAB>コメント」(UTF-8)。
 * 読み込み: 上記のほか Microsoft IME・ATOK のテキスト (UTF-16 可、! で始まる行は見出し)、
 *          Gboard の書き出し (zip / dictionary.txt)、Mac の「テキスト置換」(.plist。iPhone のユーザ辞書と同じもの)
 */
object UserDictFile {
    fun write(words: List<UserWord>): String = buildString {
        for (w in words) append(clean(w.reading)).append('\t').append(clean(w.surface)).append("\t名詞\t\n")
    }

    /** タブ・改行は入れられないので空白に */
    private fun clean(s: String) = s.replace(Regex("[\t\r\n]"), " ")

    /** (よみ, 単語)。形式が分からなければ空 */
    fun parse(bytes: ByteArray): List<Pair<String, String>> {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return parseZip(bytes)
        val text = decode(bytes)
        if (text.trimStart().startsWith("<?xml") || "<plist" in text.take(500)) return parsePlist(text)
        return parseTsv(text)
    }

    private fun parseZip(bytes: ByteArray): List<Pair<String, String>> {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".txt", ignoreCase = true)) {
                    return parseTsv(decode(z.readBytes()))
                }
            }
        }
        return emptyList()
    }

    /** BOM → UTF-8 として正しいか → 改行・タブが 2 バイトなら UTF-16 → それ以外は Shift_JIS (古い IME の書き出し) */
    private fun decode(b: ByteArray): String {
        fun u(i: Int) = b[i].toInt() and 0xFF
        return when {
            b.size >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF -> String(b, 3, b.size - 3, Charsets.UTF_8)
            b.size >= 2 && u(0) == 0xFF && u(1) == 0xFE -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            b.size >= 2 && u(0) == 0xFE && u(1) == 0xFF -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            else -> strictUtf8(b) ?: run {
                val ctl = setOf(0x09, 0x0A, 0x0D)
                val n = minOf(b.size, 4096) and 1.inv()
                val le = (0 until n step 2).count { u(it) in ctl && u(it + 1) == 0 }
                val be = (0 until n step 2).count { u(it) == 0 && u(it + 1) in ctl }
                when {
                    le > 0 && le >= be -> String(b, Charsets.UTF_16LE)
                    be > 0 -> String(b, Charsets.UTF_16BE)
                    else -> String(b, java.nio.charset.Charset.forName("windows-31j"))
                }
            }
        }
    }

    private fun strictUtf8(b: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(b)).toString()
    }.getOrNull()

    private fun parseTsv(text: String): List<Pair<String, String>> = text.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && !it.startsWith("!") && !it.startsWith("#") }
        .mapNotNull { ln ->
            val p = ln.split('\t')
            if (p.size < 2) null else p[0].trim() to p[1].trim()
        }
        .toList()

    private val PLIST_DICT = Regex("<dict>(.*?)</dict>", RegexOption.DOT_MATCHES_ALL)
    private val PLIST_PAIR = Regex("<key>\\s*(.*?)\\s*</key>\\s*<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    /** Mac の「テキスト置換」: [{phrase: 単語, shortcut: よみ}, …] */
    private fun parsePlist(text: String): List<Pair<String, String>> = PLIST_DICT.findAll(text).mapNotNull { d ->
        val kv = PLIST_PAIR.findAll(d.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        val phrase = kv["phrase"] ?: return@mapNotNull null
        val shortcut = kv["shortcut"] ?: return@mapNotNull null
        shortcut to phrase
    }.toList()

    private fun unescape(s: String) = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> {
                val cp = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                String(Character.toChars(cp))
            }
        }
    }
}
