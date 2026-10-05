package io.github.nuko6925.flickkb

/** 未確定文字列とトグル状態 */
class Composer {
    private val sb = StringBuilder()
    val text: String get() = sb.toString()
    val isEmpty: Boolean get() = sb.isEmpty()

    /** 同じキーの連打で循環中のキー */
    var toggleKey: Key? = null
        private set
    /** 最後に入力した文字の元キー (↺/↻ で循環させる対象)。キー以外の入力・編集で消える */
    private var cycleKey: Key? = null
    private var cycleIndex = 0
    private var lastLen = 0
    val isToggling: Boolean get() = toggleKey != null
    val canCycle: Boolean get() = cycleKey != null

    fun tap(key: Key) {
        if (!key.toggle) {
            // 数字キー: 連打でも毎回新しい文字
            endToggle()
            append(key.cycle[0])
            cycleKey = key
            cycleIndex = 0
            return
        }
        if (toggleKey === key && cycleKey === key && lastLen in 1..sb.length) {
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
        cycleKey = null
    }

    fun backspace() {
        dropCycle()
        if (sb.isNotEmpty()) {
            val cp = sb.codePointBefore(sb.length)
            sb.setLength(sb.length - Character.charCount(cp))
        }
    }

    fun lastChar(): Char? = sb.lastOrNull()

    fun replaceLastChar(c: Char) {
        dropCycle()
        if (sb.isNotEmpty()) sb.setCharAt(sb.length - 1, c)
    }

    /** 先頭 n 文字を消費 (文節確定) */
    fun consume(n: Int) {
        dropCycle()
        sb.delete(0, n.coerceAtMost(sb.length))
    }

    fun clear() {
        sb.setLength(0)
        dropCycle()
    }

    private fun append(s: String) {
        sb.append(s)
        lastLen = s.length
    }

    private fun replaceLast(s: String) {
        sb.setLength(sb.length - lastLen)
        sb.append(s)
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
