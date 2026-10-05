package io.github.nuko6925.flickkb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * readingLen: 確定時に未確定文字列の先頭から消費する文字数
 * learnable: 確定時に学習するか (日付など毎回変わる候補は false)
 * reading: 予測候補の本来の読み (学習時のキー)。null なら入力した読み
 */
data class Candidate(
    val text: String,
    val readingLen: Int,
    val learnable: Boolean = true,
    val reading: String? = null,
)

/**
 * 単語単位のかな漢字変換。
 * 全体一致 → 最長一致の先頭文節 → 前方一致予測 の順で候補を出す。
 * 文節区切りの最適化 (ラティス + 連接コスト) はしていない。
 */
class Converter(context: Context) {
    @Volatile private var dict: SQLiteDatabase? = null
    @Volatile private var lattice: Lattice? = null
    private val learn = LearningStore(context)
    private val user = UserDict.get(context)

    init {
        Thread {
            val db = runCatching { DictInstaller.open(context) }
                .onFailure { Log.e(TAG, "dict open failed", it) }.getOrNull()
            dict = db
            if (db != null) lattice = runCatching {
                Lattice(db, DictInstaller.install(context, "conn.bin") ?: error("conn.bin missing"), user)
            }.onFailure { Log.e(TAG, "lattice init failed", it) }.getOrNull()
        }.start()
    }

    fun candidates(reading: String): List<Candidate> {
        if (reading.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Candidate>()
        fun add(t: String, len: Int, learnable: Boolean = true, r: String? = null) {
            if (t.isNotEmpty() && t !in out) out[t] = Candidate(t, len, learnable, r)
        }
        val full = reading.length

        if (reading == "^_^") KAOMOJI.forEach { add(it, full) }

        if (reading.any { isHiragana(it) }) {
            learn.lookup(reading).forEach { add(it, full) }
            user.lookup(reading).forEach { add(it, full) }
            val lat = lattice
            val path = if (full >= 2) lat?.best(reading) else null
            val preds = if (full >= 2) predict(reading) else emptyList()
            // 予測 (ありがと → ありがとう) が文全体の変換より自然ならそちらを先に
            val p0 = preds.firstOrNull()
            if (lat != null && path != null && p0 != null && lat.singleCost(p0.cost, p0.lid, p0.rid) + PRED_MARGIN < path.cost) {
                add(p0.surface, full, r = p0.reading)
            }
            // 文全体の変換 (今日はいい天気)。複数文節の文は学習しない
            path?.let { add(it.surface, full, learnable = it.segments.size == 1) }
            val ex = exact(reading)
            ex.firstOrNull()?.let { add(it, full) }
            // 学習済みの予測 (例: きょう → 共有)
            learn.predict(reading).forEach { (r, s) -> add(s, full, r = r) }
            user.predict(reading).forEach { (r, s) -> add(s, full, r = r) }
            // 今日/昨日/明日: 先頭の変換候補の直後に日付 (iOS と同じ並び)
            DATE_READINGS[reading]?.let { d ->
                dateCandidates(LocalDate.now().plusDays(d.toLong())).forEach { add(it, full, learnable = false) }
            }
            // Typo 補正: 辞書に該当も前方一致もない時だけ
            if (full >= 3 && ex.isEmpty() && preds.isEmpty()) {
                typo(reading).forEach { (r, s) -> add(s, full, r = r) }
            }
            // 先頭文節の候補 (確定すると残りは未確定のまま続く)
            val segs = path?.segments.orEmpty()
            if (segs.size >= 2) {
                val s0 = segs[0]
                add(s0.surface, s0.len)
                exact(s0.headReading).take(12).forEach { add(it + s0.tail, s0.len) }
                exact(reading.substring(0, s0.len)).forEach { add(it, s0.len) }
            }
            ex.forEach { add(it, full) }
            add(reading, full)
            add(toKatakana(reading), full)
            if (segs.size < 2) {
                // 連接データが無い時の代替: 先頭からの最長一致
                for (len in full - 1 downTo 1) {
                    val seg = reading.substring(0, len)
                    val r = learn.lookup(seg) + exact(seg)
                    if (r.isNotEmpty()) {
                        r.forEach { add(it, len) }
                        break
                    }
                }
            }
            preds.forEach { add(it.surface, full, r = it.reading) }
            add(toHalfWidth(reading), full)
        } else {
            add(reading, full)
            if (reading.any { it in 'a'..'z' || it in 'A'..'Z' } && reading.all { it.code < 0x80 }) {
                // 入力の大文字小文字に合わせる: "Hel" → "Hello", "HEL" → "HELLO"
                val case: (String) -> String = when {
                    reading.length > 1 && reading.none { it.isLowerCase() } -> { s -> s.uppercase() }
                    reading[0].isUpperCase() -> { s -> s.replaceFirstChar { it.uppercase() } }
                    else -> { s -> s }
                }
                learn.lookup(reading).forEach { add(it, full) }
                user.lookup(reading.lowercase()).forEach { add(it, full) }
                learn.predict(reading.lowercase()).forEach { (r, s) -> add(s, full, r = r) }
                user.predict(reading.lowercase()).forEach { (r, s) -> add(s, full, r = r) }
                english(reading.lowercase()).forEach { add(case(fixI(it)), full, r = it) }
                add(reading.replaceFirstChar { it.uppercase() }, full)
                add(reading.uppercase(), full)
            }
            add(toFullWidth(reading), full)
        }
        return out.values.toList()
    }

    fun learn(reading: String, surface: String) = learn.record(reading, surface)

    /** 表記ごとに最小コストで (同じ表記が品詞違いで複数行あるため) */
    private fun exact(r: String): List<String> = query(
        "SELECT surface FROM dict WHERE reading=? AND surface<>reading GROUP BY surface ORDER BY MIN(cost) LIMIT 40",
        arrayOf(r))

    private class Pred(val reading: String, val surface: String, val cost: Int, val lid: Int, val rid: Int)

    private fun predict(r: String): List<Pred> {
        val db = dict ?: return emptyList()
        return runCatching {
            db.rawQuery("SELECT reading, surface, cost, lid, rid FROM dict WHERE reading>? AND reading<? ORDER BY cost LIMIT 30",
                arrayOf(r, r + '\uFFFF')).use { c ->
                val seen = HashSet<String>()
                buildList {
                    while (c.moveToNext() && size < 15) {
                        if (seen.add(c.getString(1)))
                            add(Pred(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4)))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    /**
     * フリックの打ち間違い補正: 同じキーの別の字・濁点/小文字の付け忘れを1文字だけ置換して
     * 完全一致 / 前方一致を探す。(読み, 表記) を最大3件
     */
    private fun typo(r: String): List<Pair<String, String>> {
        val db = dict ?: return emptyList()
        val variants = LinkedHashSet<String>()
        for (i in r.indices) {
            val g = TYPO_GROUP[r[i]] ?: continue
            for (c in g) if (c != r[i]) variants.add(r.substring(0, i) + c + r.substring(i + 1))
        }
        if (variants.isEmpty()) return emptyList()
        val hits = ArrayList<Triple<String, String, Int>>()
        runCatching {
            for (chunk in variants.chunked(400)) {
                val q = "SELECT reading, surface, MIN(cost) FROM dict WHERE reading IN (" +
                    chunk.joinToString(",") { "?" } + ") GROUP BY reading, surface ORDER BY 3 LIMIT 3"
                db.rawQuery(q, chunk.toTypedArray()).use { c ->
                    while (c.moveToNext()) hits.add(Triple(c.getString(0), c.getString(1), c.getInt(2)))
                }
            }
            for (v in variants) {
                db.rawQuery("SELECT reading, surface, cost FROM dict WHERE reading>? AND reading<? ORDER BY cost LIMIT 1",
                    arrayOf(v, v + '\uFFFF')).use { c ->
                    if (c.moveToNext()) hits.add(Triple(c.getString(0), c.getString(1), c.getInt(2)))
                }
            }
        }
        return hits.filter { it.third <= TYPO_MAX_COST }.sortedBy { it.third }
            .distinctBy { it.second }.take(3).map { it.first to it.second }
    }

    private fun english(prefix: String): List<String> = query(
        "SELECT word FROM en WHERE word>=? AND word<? ORDER BY freq DESC LIMIT 20",
        arrayOf(prefix, prefix + '\uFFFF'))

    /** i, i'm などは大文字 I */
    private fun fixI(w: String) = if (w == "i" || w.startsWith("i'")) "I" + w.substring(1) else w

    private fun query(sql: String, args: Array<String>): List<String> {
        val db = dict ?: return emptyList()
        return runCatching {
            db.rawQuery(sql, args).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        }.getOrElse { Log.w(TAG, "query failed", it); emptyList() }
    }

    companion object {
        private const val TAG = "Converter"
        private val KAOMOJI = listOf(
            "(^_^)", "(*^^*)", "(^^;", "(>_<)", "(T_T)", "(´・ω・`)", "(・∀・)",
            "m(_ _)m", "(；ω；)", "ヽ(・∀・)ノ", "(｀・ω・´)", "(￣▽￣)", "(＾ｖ＾)",
        )

        private const val PRED_MARGIN = 500
        private const val TYPO_MAX_COST = 7000
        private val TYPO_GROUP: Map<Char, String> = buildMap {
            for (g in listOf(
                "あいうえおぁぃぅぇぉゔ", "かきくけこがぎぐげご", "さしすせそざじずぜぞ",
                "たちつてとっだぢづでど", "なにぬねの", "はひふへほばびぶべぼぱぴぷぺぽ",
                "まみむめも", "やゆよゃゅょ", "らりるれろ", "わをんゎー",
            )) for (c in g) put(c, g)
        }

        private val DATE_READINGS = mapOf("きょう" to 0, "きのう" to -1, "あした" to 1, "あす" to 1)
        private val WEEK = arrayOf("月", "火", "水", "木", "金", "土", "日")

        fun dateCandidates(d: LocalDate): List<String> {
            val y = d.year
            val m = d.monthValue
            val day = d.dayOfMonth
            val w = WEEK[d.dayOfWeek.value - 1]
            val list = mutableListOf(
                "$m/$day",
                String.format(Locale.ROOT, "%04d/%02d/%02d", y, m, day),
                "${m}月${day}日 ($w)",
                "${y}年${m}月${day}日",
            )
            // 和暦 (令和以降のみ)
            if (d >= LocalDate.of(2019, 5, 1)) {
                val ry = y - 2018
                list += "令和${ry}年${m}月${day}日"
                list += String.format(Locale.ROOT, "R%02d/%02d/%02d", ry, m, day)
            }
            list += "${w}曜日"
            return list
        }

        fun isHiragana(c: Char) = c in 'ぁ'..'ゖ' || c == 'ー'

        fun toKatakana(s: String) = buildString {
            for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
        }

        fun toFullWidth(s: String) = buildString {
            for (c in s) append(when (c) {
                in '!'..'~' -> c + 0xFEE0
                ' ' -> '　'
                else -> c
            })
        }

        fun toHalfWidth(s: String) = buildString {
            for (c in s) append(when (c) {
                in '！'..'～' -> c - 0xFEE0
                '　' -> ' '
                else -> c
            })
        }
    }
}

/** assets/dict.db を内部ストレージへ展開 (APK更新時に再展開) */
object DictInstaller {
    /** assets/name を databases/ へ展開して返す。APK 更新時は再展開 */
    @Synchronized
    fun install(ctx: Context, name: String): File? {
        val dst = ctx.getDatabasePath(name)
        val stamp = File(dst.parentFile, "$name.stamp")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (!dst.exists() || !stamp.exists() || stamp.readText() != ver) {
            val names = ctx.assets.list("") ?: emptyArray()
            if (name !in names) return null
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, "$name.tmp")
            ctx.assets.open(name).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            tmp.renameTo(dst)
            stamp.writeText(ver)
        }
        return dst
    }

    fun open(ctx: Context): SQLiteDatabase? {
        val f = install(ctx, "dict.db") ?: return null
        return SQLiteDatabase.openDatabase(f.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }
}

/** 確定履歴による簡易学習 */
class LearningStore(ctx: Context) : SQLiteOpenHelper(ctx, "learn.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE learn(reading TEXT, surface TEXT, cnt INTEGER, last INTEGER, PRIMARY KEY(reading, surface))")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun lookup(reading: String): List<String> =
        readableDatabase.rawQuery(
            "SELECT surface FROM learn WHERE reading=? ORDER BY last DESC LIMIT 5", arrayOf(reading)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** 読みが prefix で始まる (prefix 自身は除く) 学習語。新しい順 */
    fun predict(prefix: String): List<Pair<String, String>> =
        readableDatabase.rawQuery(
            "SELECT reading, surface FROM learn WHERE reading>? AND reading<? ORDER BY last DESC LIMIT 3",
            arrayOf(prefix, prefix + '\uFFFF')
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    fun record(reading: String, surface: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        // API 29 の SQLite (3.22) は UPSERT 非対応なので2文で
        db.execSQL("INSERT OR IGNORE INTO learn VALUES(?,?,0,?)", arrayOf(reading, surface, now))
        db.execSQL("UPDATE learn SET cnt=cnt+1, last=? WHERE reading=? AND surface=?", arrayOf(now, reading, surface))
    }
}
