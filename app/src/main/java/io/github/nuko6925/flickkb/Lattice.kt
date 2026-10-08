package io.github.nuko6925.flickkb

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** 文節: 先頭の自立語 (head) + 付属語 (tail) */
class Segment(
    val start: Int,
    val len: Int,
    val surface: String,
    val headReading: String,
    val tail: String,
)

class Path(val segments: List<Segment>, val cost: Int) {
    val surface get() = segments.joinToString("") { it.surface }
}

/**
 * Mozc 辞書 + 連接コスト行列による最小コスト経路探索 (Viterbi)。
 * 品詞は Mozc の id.def、連接は connection_single_column.txt 由来。
 */
class Lattice(private val db: SQLiteDatabase, connFile: File, private val user: UserDict) {
    private val n: Int
    private val conn: ShortBuffer
    private val func: BooleanArray

    private class Word(val surface: String, val cost: Int, val lid: Int, val rid: Int)

    /** f: 文頭からこの語の終わりまでの最小コスト */
    private class Node(
        val f: Int, val rid: Int, val lid: Int, val cost: Int, val start: Int,
        val reading: String, val surface: String,
    )

    /** 後ろ向き探索の途中状態: node から文末までの確定済みコスト g と、その後ろの語 */
    private class Partial(val node: Node, val g: Int, val tail: Partial?) {
        val priority get() = node.f + g
    }

    /** 読み → 単語 ((lid, rid) ごとに最小コストの1語) */
    private val cache = object : LinkedHashMap<String, List<Word>>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Word>>) = size > 4096
    }

    init {
        RandomAccessFile(connFile, "r").use { f ->
            val buf = f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()).order(ByteOrder.LITTLE_ENDIAN)
            n = buf.getInt(0)
            buf.position(4)
            conn = buf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
        func = BooleanArray(n)
        db.rawQuery("SELECT id, func FROM pos", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                if (id in 0 until n) func[id] = c.getInt(1) != 0
            }
        }
    }

    fun connCost(rid: Int, lid: Int): Int = conn.get(rid * n + lid).toInt()

    /** 単語1つだけの経路としてのコスト (予測候補との比較用) */
    fun singleCost(cost: Int, lid: Int, rid: Int) = connCost(0, lid) + cost + connCost(rid, 0)

    @Synchronized
    fun best(s: String): Path? = nbest(s, 1).firstOrNull()

    /**
     * 文全体の変換を上位 n 案 (表記が異なるもの)。
     * 前向き Viterbi で各語までの最小コストを求め、文末から A* で後ろ向きに展開する
     */
    @Synchronized
    fun nbest(s: String, n: Int): List<Path> {
        val len = s.length
        if (len == 0 || len > MAX_INPUT || n <= 0) return emptyList()
        fetch(s)
        val ends = Array(len + 1) { ArrayList<Node>() }
        ends[0].add(Node(0, 0, 0, 0, -1, "", ""))  // 文頭
        for (i in 0 until len) {
            val prevs = ends[i]
            if (prevs.isEmpty()) continue
            for (j in i + 1..minOf(len, i + MAX_WORD)) {
                val r = s.substring(i, j)
                val ws = cache[r].orEmpty()
                val list = if (j == i + 1) ws + Word(r, UNK_COST, UNK_ID, UNK_ID) else ws
                for (w in list) {
                    var best = Int.MAX_VALUE
                    for (p in prevs) {
                        val t = p.f + connCost(p.rid, w.lid)
                        if (t < best) best = t
                    }
                    ends[j].add(Node(best + w.cost, w.rid, w.lid, w.cost, i, r, w.surface))
                }
            }
        }
        val heap = java.util.PriorityQueue<Partial>(compareBy { it.priority })
        for (p in ends[len]) heap.add(Partial(p, connCost(p.rid, 0), null))
        val out = ArrayList<Path>()
        val seen = HashSet<String>()
        var pops = 0
        while (heap.isNotEmpty() && out.size < n && pops < MAX_POPS) {
            val cur = heap.poll()!!
            pops++
            val node = cur.node
            if (node.start < 0) {
                // 文頭に着いた = 1案完成
                val words = generateSequence(cur.tail) { it.tail }.map { it.node }.toList()
                val path = Path(group(words), cur.g)
                if (seen.add(path.surface)) out.add(path)
                continue
            }
            for (p in ends[node.start]) {
                heap.add(Partial(p, cur.g + node.cost + connCost(p.rid, node.lid), cur))
            }
        }
        return out
    }

    /** 付属語を前の文節にまとめる */
    private fun group(words: List<Node>): List<Segment> {
        val out = ArrayList<Segment>()
        var start = 0
        var i = 0
        while (i < words.size) {
            val head = words[i]
            var j = i + 1
            while (j < words.size && func[words[j].lid]) j++
            val part = words.subList(i, j)
            val l = part.sumOf { it.reading.length }
            out.add(Segment(start, l, part.joinToString("") { it.surface }, head.reading,
                part.drop(1).joinToString("") { it.surface }))
            start += l
            i = j
        }
        return out
    }

    /** s の全部分文字列の単語をまとめて取得 (キャッシュにないものだけ) */
    private var userVersion = -1

    private fun fetch(s: String) {
        // ユーザ辞書が変わったらキャッシュを捨てる
        val v = UserDict.version.get()
        if (v != userVersion) { cache.clear(); userVersion = v }
        val need = LinkedHashSet<String>()
        for (i in s.indices) for (j in i + 1..minOf(s.length, i + MAX_WORD)) {
            val r = s.substring(i, j)
            if (!cache.containsKey(r)) need.add(r)
        }
        for (chunk in need.chunked(400)) {
            val found = lookup(chunk)
            // ゆるい読み: 濁点・半濁点・小書きの付け忘れ (ふらくしっふ → フラグシップ)
            val fz = runCatching { fuzzyReadings(chunk) }.getOrDefault(emptyMap())
            // 1文字は助詞・助動詞に限って (ひとりてかえる → 一人で帰る、ねこかすき → 猫が好き)
            val one = chunk.filter { it.length == 1 }.associateWith { Fuzzy.variants(it[0]).map(Char::toString) }
                .filterValues { it.isNotEmpty() }
            val fzWords = (fz.values.flatten() + one.values.flatten()).distinct()
                .let { if (it.isEmpty()) emptyMap() else lookup(it) }
            // ユーザ辞書の語は 名詞,一般 として低めのコストで混ぜる
            val u = runCatching { user.lookupMany(chunk) }.getOrDefault(emptyMap())
            for (r in chunk) {
                val ws = ArrayList<Word>()
                u[r]?.forEach { ws.add(Word(it, USER_COST, UNK_ID, UNK_ID)) }
                found[r]?.let { ws.addAll(it.values) }
                fz[r]?.forEach { real ->
                    // 違う文字の数だけ割増し (正しく打った語には勝たない)
                    val pen = FUZZY_PENALTY * r.indices.count { r[it] != real[it] }
                    fzWords[real]?.values?.forEach { w -> ws.add(Word(w.surface, w.cost + pen, w.lid, w.rid)) }
                }
                one[r]?.forEach { v ->
                    fzWords[v]?.values?.forEach { w ->
                        if (func[w.lid]) ws.add(Word(w.surface, w.cost + FUZZY_PENALTY, w.lid, w.rid))
                    }
                }
                cache[r] = ws
            }
        }
    }

    /** 読み → ((lid, rid) ごとに最小コストの語) */
    private fun lookup(readings: List<String>): Map<String, HashMap<Long, Word>> {
        val found = HashMap<String, HashMap<Long, Word>>()
        for (chunk in readings.chunked(400)) {
            val q = "SELECT reading, surface, cost, lid, rid FROM dict WHERE reading IN (" +
                chunk.joinToString(",") { "?" } + ")"
            db.rawQuery(q, chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val r = c.getString(0)
                    val w = Word(c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4))
                    val m = found.getOrPut(r) { HashMap() }
                    val key = (w.lid.toLong() shl 16) or w.rid.toLong()
                    val old = m[key]
                    if (old == null || w.cost < old.cost) m[key] = w
                }
            }
        }
        return found
    }

    /** 入力した読み → 同じゆるい読みを持つ本来の読み (2文字以上、自分自身は除く) */
    private fun fuzzyReadings(readings: List<String>): Map<String, List<String>> {
        val byNorm = HashMap<String, MutableList<String>>()
        for (r in readings) if (r.length >= 2) byNorm.getOrPut(Fuzzy.of(r)) { ArrayList() }.add(r)
        if (byNorm.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<String>>()
        val keys = byNorm.keys.toList()
        for (chunk in keys.chunked(400)) {
            db.rawQuery("SELECT nreading, reading FROM fuzzy WHERE nreading IN (" +
                chunk.joinToString(",") { "?" } + ")", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val real = c.getString(1)
                    for (r in byNorm[c.getString(0)].orEmpty()) if (r != real) out.getOrPut(r) { ArrayList() }.add(real)
                }
            }
        }
        return out
    }

    companion object {
        private const val MAX_INPUT = 48
        private const val MAX_POPS = 4000
        private const val MAX_WORD = 16
        private const val UNK_COST = 10000
        private const val UNK_ID = 1851  // 名詞,一般
        private const val USER_COST = 2000
        private const val FUZZY_PENALTY = 2500
    }
}

/** 濁点・半濁点・小書きを外した「ゆるい読み」。tools/build_dict.py の FUZZY と同じ表 */
object Fuzzy {
    private const val FROM = "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゔぁぃぅぇぉっゃゅょゎ"
    private const val TO = "かきくけこさしすせそたちつてとはひふへほはひふへほうあいうえおつやゆよわ"
    private val map = HashMap<Char, Char>().apply { for (i in FROM.indices) put(FROM[i], TO[i]) }

    fun of(s: String): String = buildString(s.length) { for (c in s) append(map[c] ?: c) }

    /** 同じゆるい読みになる別の文字 (て → で、か → が、は → ば・ぱ) */
    private val groups: Map<Char, List<Char>> = run {
        val all = (FROM + TO).toSet()
        all.associateWith { c -> all.filter { it != c && (map[it] ?: it) == (map[c] ?: c) } }
    }

    fun variants(c: Char): List<Char> = groups[c].orEmpty()
}
