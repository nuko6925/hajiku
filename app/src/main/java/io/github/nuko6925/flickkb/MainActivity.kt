package io.github.nuko6925.flickkb

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
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
                text = "診断ログ"
                setOnClickListener {
                    Diag.init(this@MainActivity)
                    val log = Diag.read()
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("診断ログ")
                        .setMessage(log)
                        .setPositiveButton("コピー") { _, _ ->
                            getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("diag", log))
                        }
                        .setNeutralButton("消去") { _, _ -> Diag.clear() }
                        .setNegativeButton("閉じる", null).show()
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
            addView(Button(this@MainActivity).apply {
                text = "キーボードの背景"
                setOnClickListener { chooseBackground() }
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

    // ---- キーボードの背景 ----

    private val dp get() = resources.displayMetrics.density

    private fun chooseBackground() {
        val items = arrayOf("標準", "単色…", "画像…", "画像に重ねる幕の濃さ…")
        AlertDialog.Builder(this)
            .setTitle("キーボードの背景")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> { Settings.setBgMode(this, KbBackground.DEFAULT); toast("標準の背景に戻しました") }
                    1 -> chooseColor()
                    2 -> pickImage()
                    3 -> chooseScrim()
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** 端末で既定にしている写真アプリ (ギャラリー) で選ぶ。無ければ画像を扱えるアプリから */
    private fun pickImage() {
        @Suppress("DEPRECATION")
        try {
            startActivityForResult(Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .setType("image/*"), REQ_BG_IMAGE)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, REQ_BG_IMAGE)
        }
    }

    private fun chooseColor() {
        val presets = intArrayOf(
            0xFF1C3A5E.toInt(), 0xFF2E4A3A.toInt(), 0xFF4A2E4A.toInt(), 0xFF5E2B2B.toInt(), 0xFF2B2B2D.toInt(), 0xFF000000.toInt(),
            0xFFCFE3F7.toInt(), 0xFFD8EED6.toInt(), 0xFFF3DDEB.toInt(), 0xFFF7E3C8.toInt(), 0xFFE0E1E6.toInt(), 0xFFFFFFFF.toInt(),
        )
        val hex = EditText(this).apply {
            hint = "#RRGGBB"; isSingleLine = true
            setText(String.format("#%06X", Settings.bgColor(this@MainActivity) and 0xFFFFFF))
        }
        val size = (40 * dp).toInt()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        presets.toList().chunked(6).forEach { row ->
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                for (c in row) addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(c); cornerRadius = 10 * dp; setStroke((1 * dp).toInt(), 0x33888888)
                    }
                    setOnClickListener { hex.setText(String.format("#%06X", c and 0xFFFFFF)) }
                }, LinearLayout.LayoutParams(size, size).apply { setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt()) })
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p / 2, p, 0)
            addView(grid); addView(hex)
        }
        AlertDialog.Builder(this)
            .setTitle("背景の色")
            .setView(box)
            .setPositiveButton("決定") { _, _ ->
                val c = runCatching { Color.parseColor(hex.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull()
                if (c == null) { toast("#RRGGBB の形で入力してください"); return@setPositiveButton }
                Settings.setBgColor(this, c or 0xFF000000.toInt())
                Settings.setBgMode(this, KbBackground.COLOR)
                toast("背景の色を変えました")
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun chooseScrim() {
        val label = TextView(this).apply { gravity = Gravity.CENTER; textSize = 16f }
        val bar = SeekBar(this).apply {
            max = 80
            progress = Settings.bgScrim(this@MainActivity)
            label.text = "$progress%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { label.text = "$v%" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p / 2, p, 0)
            addView(label); addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle("画像に重ねる幕の濃さ")
            .setMessage("濃くすると候補や文字が読みやすくなります (画像が暗ければ黒、明るければ白の幕)")
            .setView(box)
            .setPositiveButton("決定") { _, _ -> Settings.setBgScrim(this, bar.progress) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    @Deprecated("Activity Result API を使わない最小構成のため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_BG_IMAGE || resultCode != RESULT_OK || uri == null) return
        Thread {
            val r = runCatching { KbBackground.saveImage(applicationContext, uri) }
            runOnUiThread {
                r.onSuccess { toast("背景の画像を設定しました") }
                    .onFailure { toast("画像を読み込めませんでした: ${it.message}") }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_BG_IMAGE = 1
    }
}
