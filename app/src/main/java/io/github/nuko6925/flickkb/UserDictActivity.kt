package io.github.nuko6925.flickkb

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // ---- 書き出し / 読み込み ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_EXPORT, 0, "書き出す")
        menu.add(0, MENU_IMPORT, 1, "読み込む")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_EXPORT -> {
                if (words.isEmpty()) { toast("書き出す単語がありません"); return true }
                val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "hajiku-userdict-$date.txt")
                }, REQ_EXPORT)
            }
            MENU_IMPORT -> {
                AlertDialog.Builder(this)
                    .setTitle("ユーザ辞書を読み込む")
                    .setMessage("次の形式のファイルを読み込めます。登録済みの単語はそのまま残ります。\n\n" +
                        "・Hajiku / Google 日本語入力 / Microsoft IME / ATOK のテキスト\n" +
                        "・Gboard の書き出し (.zip)\n" +
                        "・Mac の「テキスト置換」(.plist)\n\n" +
                        "iPhone のユーザ辞書は、同じ Apple アカウントの Mac の「システム設定 › キーボード › テキスト置換…」に" +
                        "同期されています。一覧を全選択してデスクトップにドラッグすると .plist ファイルになります")
                    .setPositiveButton("ファイルを選ぶ") { _, _ ->
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        }, REQ_IMPORT)
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    @Deprecated("Activity Result API を使わない最小構成のため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> export(uri)
            REQ_IMPORT -> import(uri)
        }
    }

    private fun export(uri: Uri) {
        runCatching {
            val all = dict.all()
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(UserDictFile.write(all).toByteArray(Charsets.UTF_8)) }
            all.size
        }.onSuccess { toast("$it 語を書き出しました") }
            .onFailure { toast("書き出せませんでした: ${it.message}") }
    }

    private fun import(uri: Uri) {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)!!.use { i ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    require(out.size() <= MAX_IMPORT_BYTES) { "ファイルが大きすぎます" }
                }
                out.toByteArray()
            }
        }.getOrElse { toast("ファイルを読めませんでした: ${it.message}"); return }
        val list = runCatching { UserDictFile.parse(bytes) }.getOrDefault(emptyList())
        if (list.isEmpty()) { toast("読み込める単語がありませんでした"); return }
        val r = dict.addAll(list)
        reloadInto()
        val msg = buildString {
            append("${r.added} 語を追加しました")
            if (r.duplicate > 0) append("\n${r.duplicate} 語は登録済みでした")
            if (r.invalid > 0) append("\n${r.invalid} 語はよみがひらがな・英数字でないため読み込みませんでした")
        }
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MENU_EXPORT = 1
        private const val MENU_IMPORT = 2
        private const val REQ_EXPORT = 1
        private const val REQ_IMPORT = 2
        private const val MAX_IMPORT_BYTES = 10 * 1024 * 1024
    }
}
