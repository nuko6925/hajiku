package io.github.nuko6925.flickkb

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * キーボードの見た目 (背景・キーの透明度・文字色)。
 * 下に実物と同じ描画のプレビューを出し、変更はその場で保存・反映する
 */
class KbStyleActivity : Activity() {
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    private val panel = PanelBackground()
    private lateinit var previewRoot: FrameLayout
    private lateinit var previewKb: KeyboardView
    private lateinit var previewCands: CandidateBarView

    private lateinit var bgGroup: RadioGroup
    private lateinit var colorBox: View
    private lateinit var imageBox: View
    private lateinit var colorHex: EditText
    private lateinit var keyBox: LinearLayout
    private lateinit var opacityLabel: TextView
    private lateinit var opacityBar: SeekBar
    private lateinit var textGroup: RadioGroup
    private lateinit var textHex: EditText

    /** コードから値を入れている間はリスナーで保存しない */
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "キーボードの見た目"
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(12f), px(20f), px(16f))
        }

        // ---- 背景 ----
        controls.addView(heading("背景"))
        bgGroup = radioGroup("標準", "単色", "画像")
        controls.addView(bgGroup)
        colorHex = hexField()
        colorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(swatches(BG_PRESETS) { c -> colorHex.setText(hex(c)) })
            addView(colorHex)
        }
        controls.addView(colorBox)
        val scrimLabel = TextView(this)
        imageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@KbStyleActivity).apply { text = "画像を選ぶ…"; setOnClickListener { pickImage() } })
            addView(scrimLabel)
            addView(slider(0, 80, Settings.bgScrim(this@KbStyleActivity)) { v, fromUser ->
                scrimLabel.text = "画像に重ねる幕の濃さ: $v%  (暗い画像には黒、明るい画像には白)"
                if (fromUser) { Settings.setBgScrim(this@KbStyleActivity, v); refresh() }
            })
        }
        controls.addView(imageBox)

        // ---- キー ----
        controls.addView(heading("キーの不透明度"))
        opacityLabel = TextView(this)
        opacityBar = slider(0, 100, 0) { v, fromUser ->
            if (fromUser) { Settings.setKeyOpacity(this, v); refresh() }
        }
        keyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(opacityLabel)
            addView(opacityBar)
            addView(Button(this@KbStyleActivity).apply {
                text = "自動に戻す"
                setOnClickListener { Settings.setKeyOpacity(this@KbStyleActivity, -1); load() }
            })
        }
        controls.addView(keyBox)

        // ---- 文字色 ----
        controls.addView(heading("文字の色"))
        textGroup = radioGroup("自動", "白", "黒", "指定")
        controls.addView(textGroup)
        textHex = hexField()
        controls.addView(swatches(TEXT_PRESETS) { c -> textHex.setText(hex(c)); textGroup.check(textGroup.getChildAt(3).id) })
        controls.addView(textHex)

        controls.addView(Button(this).apply {
            text = "すべて元に戻す"
            setOnClickListener { Settings.resetStyle(this@KbStyleActivity); load() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16f) })

        // ---- プレビュー (実物と同じ部品・同じ描画) ----
        previewCands = CandidateBarView(this, {}, {})
        previewKb = KeyboardView(this, NoInput)
        val previewCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(previewCands, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(40f)))
            addView(previewKb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        previewRoot = TouchBlocker(this).apply {
            background = panel
            setPadding(0, 0, 0, px(8f))
            addView(previewCol)
        }
        val top = px(26f).toFloat()
        val bottom = px(28f).toFloat()
        panel.cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        previewCands.set(listOf("今日", "京", "強", "教", "きょう").map { Candidate(it, 3) }, -1)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@KbStyleActivity).apply { addView(controls) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(TextView(this@KbStyleActivity).apply {
                text = "プレビュー"; textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, px(6f), 0, px(6f))
            })
            addView(previewRoot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })

        // ---- 変更 → 保存 → 反映 ----
        bgGroup.setOnCheckedChangeListener { g, id ->
            if (loading) return@setOnCheckedChangeListener
            when (g.indexOfChild(g.findViewById(id))) {
                0 -> Settings.setBgMode(this, KbBackground.DEFAULT)
                1 -> Settings.setBgMode(this, KbBackground.COLOR)
                2 -> if (KbBackground.file(this).exists()) Settings.setBgMode(this, KbBackground.IMAGE) else pickImage()
            }
            load()
        }
        colorHex.addTextChangedListener(onHex { c -> Settings.setBgColor(this, c); Settings.setBgMode(this, KbBackground.COLOR); refresh() })
        textGroup.setOnCheckedChangeListener { g, id ->
            if (loading) return@setOnCheckedChangeListener
            Settings.setTextMode(this, g.indexOfChild(g.findViewById(id)))
            load()
        }
        textHex.addTextChangedListener(onHex { c -> Settings.setTextColor(this, c); Settings.setTextMode(this, 3); load() })
        load()
    }

    /** 設定値を画面に入れ直してプレビューを更新 */
    private fun load() {
        loading = true
        val mode = Settings.bgMode(this)
        bgGroup.check(bgGroup.getChildAt(mode.coerceIn(0, 2)).id)
        colorBox.visibility = if (mode == KbBackground.COLOR) View.VISIBLE else View.GONE
        imageBox.visibility = if (mode == KbBackground.IMAGE) View.VISIBLE else View.GONE
        if (!colorHex.hasFocus()) colorHex.setText(hex(Settings.bgColor(this)))
        val tm = Settings.textMode(this)
        textGroup.check(textGroup.getChildAt(tm.coerceIn(0, 3)).id)
        textHex.visibility = if (tm == 3) View.VISIBLE else View.GONE
        if (!textHex.hasFocus()) textHex.setText(hex(Settings.textColor(this)))
        loading = false
        refresh()
    }

    /** プレビューに今の設定を反映 */
    private fun refresh() {
        val t = KbBackground.applyTo(this, panel, isDark())
        previewKb.theme = t
        previewCands.theme = t
        previewKb.backdrop = panel
        previewKb.backdropRoot = previewRoot
        // 不透明度は背景を設定した時だけ効く
        val glass = t.glass
        keyBox.alpha = if (glass) 1f else 0.4f
        opacityBar.isEnabled = glass
        if (!opacityBar.isPressed) opacityBar.progress = t.keyOpacity
        val auto = Settings.keyOpacity(this) < 0
        opacityLabel.text = if (glass) "${t.keyOpacity}%" + (if (auto) " (自動)" else "")
            else "背景を単色・画像にすると変えられます"
        panel.invalidateSelf()
        previewRoot.invalidate()
        previewKb.invalidate()
    }

    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ---- 画像 ----

    /** 端末で既定にしている写真アプリ (ギャラリー) で選ぶ。無ければ画像を扱えるアプリから */
    private fun pickImage() {
        @Suppress("DEPRECATION")
        try {
            startActivityForResult(Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .setType("image/*"), REQ_IMAGE)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, REQ_IMAGE)
        }
    }

    @Deprecated("Activity Result API を使わない最小構成のため")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_IMAGE) return
        if (resultCode != RESULT_OK || uri == null) { load(); return }  // やめた時は元の選択に戻す
        Thread {
            val r = runCatching { KbBackground.saveImage(applicationContext, uri) }
            runOnUiThread {
                r.onFailure { toast("画像を読み込めませんでした: ${it.message}") }
                load()
            }
        }.start()
    }

    // ---- 部品 ----

    private fun heading(s: String) = TextView(this).apply {
        text = s; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(16f), 0, px(4f))
    }

    private fun radioGroup(vararg labels: String) = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        for (l in labels) addView(RadioButton(this@KbStyleActivity).apply { text = l; id = View.generateViewId() })
    }

    private fun slider(min: Int, max: Int, value: Int, onChange: (Int, Boolean) -> Unit) = SeekBar(this).apply {
        this.min = min
        this.max = max
        progress = value
        onChange(value, false)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) = onChange(v, fromUser)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun swatches(colors: IntArray, onPick: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        colors.toList().chunked(6).forEach { row ->
            addView(LinearLayout(this@KbStyleActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                for (c in row) addView(View(this@KbStyleActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(c); cornerRadius = 10 * dp; setStroke(px(1f).coerceAtLeast(1), 0x55888888)
                    }
                    setOnClickListener { onPick(c) }
                }, LinearLayout.LayoutParams(px(38f), px(38f)).apply { setMargins(px(4f), px(4f), px(4f), px(4f)) })
            })
        }
    }

    private fun hexField() = EditText(this).apply { hint = "#RRGGBB"; isSingleLine = true }

    /** 正しい #RRGGBB になった時だけ呼ぶ */
    private fun onHex(apply: (Int) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: Editable?) {
            if (loading) return
            val t = e?.toString()?.trim()?.removePrefix("#") ?: return
            if (t.length != 6) return
            val c = t.toIntOrNull(16) ?: return
            apply(c or 0xFF000000.toInt())
        }
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** プレビューは触っても入力しない */
    private class TouchBlocker(ctx: android.content.Context) : FrameLayout(ctx) {
        override fun onInterceptTouchEvent(ev: MotionEvent) = true
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent) = true
    }

    private object NoInput : KeyboardView.Listener {
        override fun onCharTap(key: Key) {}
        override fun onCharFlick(key: Key, s: String) {}
        override fun onFunction(key: Key) {}
        override fun onFunctionLongPress(key: Key) = false
        override fun onCursorMove(dx: Int, dy: Int) {}
    }

    companion object {
        private const val REQ_IMAGE = 1
        private val BG_PRESETS = intArrayOf(
            0xFF1C3A5E.toInt(), 0xFF2E4A3A.toInt(), 0xFF4A2E4A.toInt(), 0xFF5E2B2B.toInt(), 0xFF2B2B2D.toInt(), 0xFF000000.toInt(),
            0xFFCFE3F7.toInt(), 0xFFD8EED6.toInt(), 0xFFF3DDEB.toInt(), 0xFFF7E3C8.toInt(), 0xFFE0E1E6.toInt(), 0xFFFFFFFF.toInt(),
        )
        private val TEXT_PRESETS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD60A.toInt(), 0xFF64D2FF.toInt(), 0xFFFF9FF3.toInt(), 0xFF30D158.toInt(),
        )
    }
}
