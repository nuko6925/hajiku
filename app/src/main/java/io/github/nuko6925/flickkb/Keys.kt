package io.github.nuko6925.flickkb

enum class Mode { KANA, ALPHA, NUM }

enum class KeyType {
    CHAR, CURSOR_NEXT, TOGGLE_BACK, TOGGLE_FWD, MODE, DELETE, SPACE, ENTER, MODIFIER, CASE, EMOJI
}

/** フリック方向。flick 配列の添字に対応 */
object Dir {
    const val NONE = -1
    const val C = 0
    const val L = 1
    const val U = 2
    const val R = 3
    const val D = 4
}

class Key(
    val type: KeyType,
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val label: String = "",
    /** 数字モードのサブラベル (☆♪→ など) */
    val sub: String? = null,
    /** [中央, 左, 上, 右, 下] */
    val flick: Array<String?> = arrayOfNulls(5),
    /** トグル入力の循環 */
    val cycle: List<String> = emptyList(),
    val targetMode: Mode? = null,
    /** false: 連打しても循環せず毎回 cycle[0] を入力 (数字キー) */
    val toggle: Boolean = true,
) {
    val isFunction get() = type != KeyType.CHAR
}

object Layouts {
    private fun chars(s: String) = s.map { it.toString() }

    private fun ch(
        col: Int, row: Int, label: String,
        flick: List<String?>, cycle: List<String> = flick.filterNotNull(), sub: String? = null,
        toggle: Boolean = true,
    ) = Key(KeyType.CHAR, col, row, label = label, sub = sub,
        flick = Array(5) { flick.getOrNull(it) }, cycle = cycle, toggle = toggle)

    /** 5方向すべて埋まるかな行 */
    private fun kana(col: Int, row: Int, five: String, extra: String = "") =
        ch(col, row, five.take(1), chars(five), chars(five + extra))

    private fun common(left0: KeyType, modeLabel: String, target: Mode) = listOf(
        Key(left0, 0, 0),
        Key(KeyType.TOGGLE_BACK, 0, 1),
        Key(KeyType.MODE, 0, 2, rowSpan = 2, label = modeLabel, targetMode = target),
        Key(KeyType.DELETE, 4, 0),
        Key(KeyType.SPACE, 4, 1),
        Key(KeyType.ENTER, 4, 2, rowSpan = 2),
        Key(KeyType.EMOJI, 0, 4),
    )

    val kana: List<Key> = common(KeyType.CURSOR_NEXT, "ABC", Mode.ALPHA) + listOf(
        kana(1, 0, "あいうえお", "ぁぃぅぇぉ"),
        kana(2, 0, "かきくけこ"),
        kana(3, 0, "さしすせそ"),
        kana(1, 1, "たちつてと", "っ"),
        kana(2, 1, "なにぬねの"),
        kana(3, 1, "はひふへほ"),
        kana(1, 2, "まみむめも"),
        ch(2, 2, "や", listOf("や", "（", "ゆ", "）", "よ"), chars("やゆよゃゅょ")),
        kana(3, 2, "らりるれろ"),
        Key(KeyType.MODIFIER, 1, 3),
        ch(2, 3, "わ_", listOf("わ", "を", "ん", "ー", null), chars("わをんゎー")),
        ch(3, 3, "、。?!", listOf("、", "。", "？", "！", null), chars("、。？！…")),
    )

    val alpha: List<Key> = common(KeyType.CURSOR_NEXT, "☆123", Mode.NUM) + listOf(
        ch(1, 0, "@#/&_", chars("@#/&_")),
        ch(2, 0, "ABC", chars("abc")),
        ch(3, 0, "DEF", chars("def")),
        ch(1, 1, "GHI", chars("ghi")),
        ch(2, 1, "JKL", chars("jkl")),
        ch(3, 1, "MNO", chars("mno")),
        ch(1, 2, "PQRS", chars("pqrs")),
        ch(2, 2, "TUV", chars("tuv")),
        ch(3, 2, "WXYZ", chars("wxyz")),
        Key(KeyType.CASE, 1, 3, label = "a/A"),
        ch(2, 3, "’”()", chars("'\"()")),
        ch(3, 3, ".,?!", chars(".,?!")),
    )

    /** 数字キー: 連打で同じ数字が続く (iOS と同じ)。記号はフリックで */
    private fun num(col: Int, row: Int, s: String) =
        ch(col, row, s.take(1), chars(s), sub = s.drop(1), toggle = false)

    val num: List<Key> = common(KeyType.TOGGLE_FWD, "あいう", Mode.KANA) + listOf(
        num(1, 0, "1☆♪→"), num(2, 0, "2¥$€"), num(3, 0, "3%°#"),
        num(1, 1, "4○*・"), num(2, 1, "5+×÷"), num(3, 1, "6<=>"),
        num(1, 2, "7「」:"), num(2, 2, "8〒々〆"), num(3, 2, "9^|\\"),
        ch(1, 3, "()[]", chars("()[]")),
        num(2, 3, "0〜…"),
        ch(3, 3, ".,-/", chars(".,-/")),
    )

    fun of(mode: Mode) = when (mode) {
        Mode.KANA -> kana
        Mode.ALPHA -> alpha
        Mode.NUM -> num
    }
}
