package io.github.nuko6925.flickkb

/** 未確定文字列・カーソル位置・トグル状態 */
class Composer {
    private val sb = StringBuilder()
    val text: String get() = sb.toString()
    val isEmpty: Boolean get() = sb.isEmpty()
    val length: Int get() = sb.length

    /** 未確定文字列内のカーソル位置 (0..length)。入力・削除はここで行う */
    var cursor = 0
        private set
    val cursorAtEnd: Boolean get() = cursor == sb.length

    /** 同じキーの連打で循環中のキー */
    var toggleKey: Key? = null
        private set
    /** 最後に入力した文字の元キー (↺/↻ で循環させる対象)。キー以外の入力・編集で消える */
    private var cycleKey: Key? = null
    private var cycleIndex = 0
    private var lastLen = 0
    /** ↺/↻ で循環させた直後 (次に同じキーを押すと新しい文字になる) */
    private var steppedByArrow = false
    val isToggling: Boolean get() = toggleKey != null
    val canCycle: Boolean get() = cycleKey != null
    /** かな・英字で ↺/↻ が効くか: 連打中か、↺/↻ で循環させた直後 */
    val canArrowCycle: Boolean get() = cycleKey != null && (toggleKey != null || steppedByArrow)

    fun tap(key: Key) {
        if (!key.toggle) {
            // 数字キー: 連打でも毎回新しい文字
            endToggle()
            append(key.cycle[0])
            cycleKey = key
            cycleIndex = 0
            return
        }
        if (toggleKey === key && cycleKey === key && lastLen in 1..cursor) {
            stepCycle(+1)
        } else {
            toggleKey = key
            cycleKey = key
            cycleIndex = 0
            append(key.cycle[0])
        }
    }

    fun flick(key: Key, s: String) {
        endToggle()
        append(s)
        cycleIndex = key.cycle.indexOf(s)
        cycleKey = if (cycleIndex >= 0) key else null
    }

    /** ^_^ 等、キーの循環に属さない追加 */
    fun insert(s: String) {
        endToggle()
        append(s)
        cycleKey = null
    }

    /**
     * ↺/↻ キー: 最後の1文字を循環させる。iOS と同じく、この後に同じキーを押すと
     * 循環の続きではなく新しい文字になる (↺/↻ 自体は続けて使える)
     */
    fun stepByArrow(delta: Int) {
        if (cycleKey == null) return
        stepCycle(delta)
        toggleKey = null
        steppedByArrow = true
    }

    /** 最後の1文字を元キーの循環で前後に送る */
    fun stepCycle(delta: Int) {
        val k = cycleKey ?: return
        cycleIndex = Math.floorMod(cycleIndex + delta, k.cycle.size)
        replaceLast(k.cycle[cycleIndex])
    }

    fun endToggle() {
        toggleKey = null
    }

    private fun dropCycle() {
        endToggle()
        steppedByArrow = false
        cycleKey = null
    }

    /** カーソルの前の1文字を削除 */
    fun backspace() {
        dropCycle()
        if (cursor > 0) {
            val n = Character.charCount(sb.codePointBefore(cursor))
            sb.delete(cursor - n, cursor)
            cursor -= n
        }
    }

    /** カーソルの直前の文字 (小゛゜ や a/A の対象) */
    fun lastChar(): Char? = if (cursor > 0) sb[cursor - 1] else null

    fun replaceLastChar(c: Char) {
        dropCycle()
        if (cursor > 0) sb.setCharAt(cursor - 1, c)
    }

    /** カーソルを動かす。トグルは終了 */
    fun moveCursor(delta: Int) {
        dropCycle()
        var c = cursor
        repeat(kotlin.math.abs(delta)) {
            c = if (delta > 0) {
                if (c < sb.length) c + Character.charCount(sb.codePointAt(c)) else c
            } else {
                if (c > 0) c - Character.charCount(sb.codePointBefore(c)) else c
            }
        }
        cursor = c
    }

    /** 外部 (エディタ上のタップ等) からカーソル位置を合わせる */
    fun setCursor(pos: Int) {
        if (pos == cursor) return
        dropCycle()
        cursor = pos.coerceIn(0, sb.length)
    }

    /** 先頭 n 文字を消費 (文節確定) */
    fun consume(n: Int) {
        dropCycle()
        val k = n.coerceAtMost(sb.length)
        sb.delete(0, k)
        cursor = (cursor - k).coerceAtLeast(0)
    }

    fun clear() {
        sb.setLength(0)
        cursor = 0
        dropCycle()
    }

    private fun append(s: String) {
        steppedByArrow = false
        sb.insert(cursor, s)
        cursor += s.length
        lastLen = s.length
    }

    private fun replaceLast(s: String) {
        sb.replace(cursor - lastLen, cursor, s)
        cursor += s.length - lastLen
        lastLen = s.length
    }
}

/** 小゛゜キーの循環 */
object KanaModifier {
    private val cycles = listOf(
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
        "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "やゃ", "ゆゅ", "よょ", "わゎ",
    )
    private val map = HashMap<Char, Char>().apply {
        for (c in cycles) for (i in c.indices) put(c[i], c[(i + 1) % c.length])
    }

    fun next(c: Char): Char? = map[c]
}
