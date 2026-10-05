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

    private class Node(
        val total: Int, val rid: Int, val prev: Node?,
        val reading: String, val surface: String, val lid: Int,
    )

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
    fun best(s: String): Path? {
        val len = s.length
        if (len == 0 || len > MAX_INPUT) return null
        fetch(s)
        val ends = Array(len + 1) { ArrayList<Node>() }
        ends[0].add(Node(0, 0, null, "", "", 0))
        for (i in 0 until len) {
            val prevs = ends[i]
            if (prevs.isEmpty()) continue
            for (j in i + 1..minOf(len, i + MAX_WORD)) {
                val r = s.substring(i, j)
                val ws = cache[r].orEmpty()
                val list = if (j == i + 1) ws + Word(r, UNK_COST, UNK_ID, UNK_ID) else ws
                for (w in list) {
                    var bestNode: Node? = null
                    var bestCost = Int.MAX_VALUE
                    for (p in prevs) {
                        val t = p.total + connCost(p.rid, w.lid) + w.cost
                        if (t < bestCost) { bestCost = t; bestNode = p }
                    }
                    ends[j].add(Node(bestCost, w.rid, bestNode, r, w.surface, w.lid))
                }
            }
        }
        var last: Node? = null
        var total = Int.MAX_VALUE
        for (p in ends[len]) {
            val t = p.total + connCost(p.rid, 0)
            if (t < total) { total = t; last = p }
        }
        val words = ArrayList<Node>()
        var cur = last
        while (cur?.prev != null) { words.add(cur); cur = cur.prev }
        words.reverse()
        return Path(group(words), total)
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
            val found = HashMap<String, HashMap<Long, Word>>()
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
            // ユーザ辞書の語は 名詞,一般 として低めのコストで混ぜる
            val u = runCatching { user.lookupMany(chunk) }.getOrDefault(emptyMap())
            for (r in chunk) {
                val ws = found[r]?.values?.toList().orEmpty()
                cache[r] = u[r]?.map { Word(it, USER_COST, UNK_ID, UNK_ID) }?.plus(ws) ?: ws
            }
        }
    }

    companion object {
        private const val MAX_INPUT = 48
        private const val MAX_WORD = 16
        private const val UNK_COST = 10000
        private const val UNK_ID = 1851  // 名詞,一般
        private const val USER_COST = 2000
    }
}
