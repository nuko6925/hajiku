package io.github.nuko6925.flickkb

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class UserDictActivity : Activity() {
    private lateinit var dict: UserDict
    private lateinit var empty: TextView
    private var words: List<UserWord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ユーザ辞書"
        dict = UserDict.get(this)
        val p = (16 * resources.displayMetrics.density).toInt()
        val list = object : ArrayAdapter<UserWord>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) =
                super.getView(position, convertView, parent).also { v ->
                    val w = getItem(position)!!
                    v.findViewById<TextView>(android.R.id.text1)?.text = w.surface
                    v.findViewById<TextView>(android.R.id.text2)?.text = w.reading
                }
        }
        empty = TextView(this).apply {
            text = "単語が登録されていません"
            gravity = Gravity.CENTER
            setPadding(p, p * 2, p, p)
        }
        val lv = ListView(this).apply {
            this.adapter = list
            setOnItemClickListener { _, _, pos, _ -> edit(words[pos]) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 2, p, 0)
            addView(Button(this@UserDictActivity).apply {
                text = "＋ 単語を追加"
                setOnClickListener { edit(null) }
            })
            addView(empty)
            addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        reloadInto = {
            words = dict.all()
            list.clear(); list.addAll(words)
            empty.visibility = if (words.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        reloadInto()
    }

    private var reloadInto: () -> Unit = {}

    /** w == null: 新規 */
    private fun edit(w: UserWord?) {
        val p = (20 * resources.displayMetrics.density).toInt()
        val surface = EditText(this).apply { hint = "単語"; setText(w?.surface.orEmpty()); isSingleLine = true }
        val reading = EditText(this).apply {
            hint = "よみ (ひらがな・英数字)"
            setText(w?.reading.orEmpty())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p / 2, p, 0)
            addView(surface); addView(reading)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (w == null) "単語を追加" else "単語を編集")
            .setView(form)
            .setPositiveButton("保存", null)
            .setNegativeButton("キャンセル", null)
        if (w != null) b.setNeutralButton("削除") { _, _ -> dict.delete(w.id); reloadInto() }
        val dlg = b.create()
        dlg.setOnShowListener {
            // 入力が不正なら閉じない
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = surface.text.toString().trim()
                val r = reading.text.toString()
                when {
                    s.isEmpty() -> toast("単語を入力してください")
                    !UserDict.isValidReading(r) -> toast("よみはひらがな・英数字で入力してください")
                    else -> {
                        val ok = if (w == null) dict.add(r, s) else dict.update(w.id, r, s)
                        if (!ok) toast("同じ単語が既に登録されています") else { reloadInto(); dlg.dismiss() }
                    }
                }
            }
        }
        dlg.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
