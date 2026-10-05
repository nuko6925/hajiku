package io.github.nuko6925.flickkb

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** assets/licenses/ のライセンス全文を表示 */
class LicensesActivity : Activity() {
    private val entries = listOf(
        "Hajiku" to "hajiku.txt",
        "Mozc" to "mozc.txt",
        "Mozc OSS 辞書 (IPAdic / ICOT / 沖縄辞書)" to "mozc-dictionary.txt",
        "FrequencyWords 英単語データ (CC BY-SA 4.0)" to "frequencywords-data.txt",
        "FrequencyWords" to "frequencywords-code.txt",
        "emoji-data" to "emoji-data.txt",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ライセンス"
        val p = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 2, p, p * 2)
        }
        for ((name, file) in entries) {
            val body = runCatching { assets.open("licenses/$file").bufferedReader().readText() }.getOrNull() ?: continue
            col.addView(TextView(this).apply {
                text = name
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, p, 0, p / 2)
            })
            col.addView(TextView(this).apply {
                text = body
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
