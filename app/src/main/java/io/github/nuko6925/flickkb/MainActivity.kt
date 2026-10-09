package io.github.nuko6925.flickkb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = (20 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 2, p, p)
            addView(TextView(this@MainActivity).apply {
                text = "1. 下のボタンから「Hajiku」を有効化\n2. 入力方法を切り替え\n3. 下の欄で試し打ち"
                textSize = 16f
            })
            addView(Button(this@MainActivity).apply {
                text = "キーボードを有効化"
                setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "ユーザ辞書"
                setOnClickListener { startActivity(Intent(this@MainActivity, UserDictActivity::class.java)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "パスワード"
                setOnClickListener { startActivity(Intent(this@MainActivity, VaultActivity::class.java)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "Hajiku を自動入力サービスにする"
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                            android.net.Uri.parse("package:$packageName")))
                    }
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "ライセンス"
                setOnClickListener { startActivity(Intent(this@MainActivity, LicensesActivity::class.java)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "入力方法を切り替え"
                setOnClickListener {
                    getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                }
            })
            addView(Switch(this@MainActivity).apply {
                text = "かなモードの空白を全角にする"
                textSize = 16f
                isChecked = Settings.fullwidthSpace(this@MainActivity)
                setOnCheckedChangeListener { _, v -> Settings.setFullwidthSpace(this@MainActivity, v) }
                setPadding(0, p / 2, 0, p / 2)
            })
            addView(EditText(this@MainActivity).apply { hint = "ここで入力テスト"; minLines = 3 })
        })
    }
}
