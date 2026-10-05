package io.github.nuko6925.flickkb

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout

class FlickImeService : InputMethodService(), KeyboardView.Listener, EmojiPanelView.Listener {

    private lateinit var keyboard: KeyboardView
    private lateinit var candBar: CandidateBarView
    private lateinit var emojiPanel: EmojiPanelView
    private lateinit var candPanel: CandidatePanelView
    private lateinit var kbContainer: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var converter: Converter

    private val composer = Composer()
    private var cands: List<Candidate> = emptyList()
    private var selected = -1
    private var composingShown = false

    /** パスワード・数値欄など: 変換せず直接入力 */
    private var direct = false
    private var noLearn = false
    private var fullwidthSpace = false

    /** iOS 風の角丸パネル。角の外側は透過してアプリが見える */
    private val panelBg = GradientDrawable()
    private var bottomRadius = 0f

    private fun radii(): FloatArray {
        val top = 26 * resources.displayMetrics.density
        val b = if (bottomRadius > 0f) bottomRadius else 28 * resources.displayMetrics.density
        return floatArrayOf(top, top, top, top, b, b, b, b)
    }

    override fun onCreate() {
        super.onCreate()
        converter = Converter(applicationContext)
    }

    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        candBar = CandidateBarView(this, { commitCandidate(it) }, { showCandPanel(true) })
        candPanel = CandidatePanelView(this, { i -> showCandPanel(false); commitCandidate(i) }, { showCandPanel(false) })
            .apply { visibility = View.GONE }
        keyboard = KeyboardView(this, this)
        emojiPanel = EmojiPanelView(this, this).apply { visibility = View.GONE }
        kbContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false   // 上段フリックのポップアップを候補バー上に描くため
            clipToPadding = false
            addView(candBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * dp).toInt()))
            addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(kbContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // 絵文字パネルはキーボード部(候補バー込み)と同じ大きさで重ねる
            addView(emojiPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(candPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = if (Build.VERSION.SDK_INT >= 30)
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                v.setPadding(0, 0, 0, bottom)
                // 下側の角丸は画面の角に合わせる (取れなければ既定値)
                if (Build.VERSION.SDK_INT >= 31) {
                    val rc = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                    bottomRadius = rc?.radius?.toFloat()?.takeIf { it > 0f } ?: (28 * dp)
                    panelBg.cornerRadii = radii()
                }
                insets
            }
        }
        applyTheme()
        // 角丸の外側を透過させる
        window?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return root
    }

    private fun applyTheme() {
        val t = KbTheme(isDark())
        keyboard.theme = t
        candBar.theme = t
        emojiPanel.theme = t
        candPanel.theme = t
        panelBg.setColor(t.bg)
        panelBg.cornerRadii = radii()
        root.background = panelBg
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyTheme()
        reset()
        showEmoji(false)
        showCandPanel(false)
        fullwidthSpace = Settings.fullwidthSpace(this)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val alphaVariation = variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        keyboard.mode = when {
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME -> Mode.NUM
            cls == InputType.TYPE_CLASS_TEXT && (password || alphaVariation) -> Mode.ALPHA
            else -> Mode.KANA
        }
        direct = password || cls != InputType.TYPE_CLASS_TEXT || info.inputType == InputType.TYPE_NULL
        noLearn = password || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        updateUi()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        currentInputConnection?.finishComposingText()
        reset()
    }

    private fun reset() {
        composer.clear()
        cands = emptyList()
        selected = -1
        composingShown = false
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // 未確定中にカーソルがよそへ動いた (ユーザーのタップ等) → 未確定を破棄して確定扱い
        if (!composer.isEmpty &&
            (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            reset()
            updateUi()
        }
    }

    // ---- KeyboardView.Listener ----

    override fun onCharTap(key: Key) = edit {
        if (selected >= 0) commitSelectedInBatch()
        if (direct && composer.toggleKey !== key) flushDirect()
        composer.tap(key)
        afterInput()
    }

    override fun onCharFlick(key: Key, s: String) = edit {
        if (selected >= 0) commitSelectedInBatch()
        if (direct) flushDirect()
        // 直接入力でも最後の1文字は未確定に残す (↺/↻ で変えられるように)。次の入力で確定
        composer.flick(key, s)
        afterInput()
    }

    override fun onFunction(key: Key) {
        val ic = currentInputConnection
        when (key.type) {
            KeyType.CURSOR_NEXT -> edit {
                if (composer.isToggling) {
                    composer.endToggle()
                    if (direct) flushDirect()
                } else if (composer.isEmpty) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
                updateUi()
            }
            KeyType.TOGGLE_BACK -> edit { if (cycleEnabled()) { composer.stepCycle(-1); afterInput() } }
            KeyType.TOGGLE_FWD -> edit { if (cycleEnabled()) { composer.stepCycle(+1); afterInput() } }
            KeyType.MODE -> edit {
                composer.endToggle()
                if (direct) flushDirect()
                keyboard.mode = key.targetMode!!
                updateUi()
            }
            KeyType.DELETE -> edit {
                when {
                    selected >= 0 -> { selected = -1; showComposing(); updateUi() }
                    !composer.isEmpty -> { composer.backspace(); afterInput() }
                    else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
            }
            KeyType.SPACE -> edit {
                if (!composer.isEmpty && !direct && cands.isNotEmpty()) {
                    selected = (selected + 1) % cands.size
                    showComposing()
                    updateUi()
                } else {
                    flushDirect()
                    ic?.commitText(if (keyboard.mode == Mode.KANA && fullwidthSpace && !direct) "　" else " ", 1)
                    updateUi()
                }
            }
            KeyType.ENTER -> edit {
                when {
                    selected >= 0 -> commitCandidateInBatch(selected)
                    !composer.isEmpty -> flushDirect()
                    else -> enterAction()
                }
                updateUi()
            }
            KeyType.MODIFIER -> edit {
                val n = composer.lastChar()?.let { KanaModifier.next(it) }
                if (n != null && selected < 0) {
                    composer.replaceLastChar(n)
                } else {
                    if (selected >= 0) commitSelectedInBatch()
                    if (direct) flushDirect()
                    composer.insert("^_^")
                }
                afterInput()
            }
            KeyType.CASE -> edit {
                val c = composer.lastChar()
                if (c != null && c.isLetter() && selected < 0) {
                    composer.replaceLastChar(if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar())
                    afterInput()
                }
            }
            KeyType.EMOJI -> showEmoji(true)
            KeyType.CHAR -> {}
        }
    }

    override fun onFunctionLongPress(key: Key): Boolean = when (key.type) {
        // 未確定中でも可 (iOS と同じ)。未確定は確定してからカーソルを動かす
        KeyType.SPACE -> {
            edit { commitAll(); updateUi() }
            true
        }
        KeyType.EMOJI -> {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            true
        }
        else -> false
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        val code = when {
            dx < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dy < 0 -> KeyEvent.KEYCODE_DPAD_UP
            else -> KeyEvent.KEYCODE_DPAD_DOWN
        }
        sendDownUpKeyEvents(code)
    }

    // ---- 絵文字パネル ----

    private fun showEmoji(show: Boolean) {
        if (!::emojiPanel.isInitialized) return
        if (show) {
            showCandPanel(false)
            edit { commitAll(); updateUi() }
            emojiPanel.backLabel = when (keyboard.mode) {
                Mode.KANA -> "あいう"
                Mode.ALPHA -> "ABC"
                Mode.NUM -> "☆123"
            }
            emojiPanel.show(EmojiRecents.get(this))
            emojiPanel.visibility = View.VISIBLE
            kbContainer.visibility = View.INVISIBLE   // 高さは維持
        } else {
            emojiPanel.visibility = View.GONE
            kbContainer.visibility = View.VISIBLE
        }
    }

    // ---- 候補の展開 ----

    private fun showCandPanel(show: Boolean) {
        if (!::candPanel.isInitialized) return
        if (show && cands.isNotEmpty()) {
            candPanel.set(cands, selected)
            candPanel.visibility = View.VISIBLE
            kbContainer.visibility = View.INVISIBLE
        } else if (candPanel.visibility != View.GONE) {
            candPanel.visibility = View.GONE
            kbContainer.visibility = View.VISIBLE
        }
    }

    override fun onEmoji(e: String) {
        currentInputConnection?.commitText(e, 1)
        if (!noLearn) EmojiRecents.add(this, e)
    }

    override fun onEmojiDelete() = sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)

    override fun onEmojiClose() = showEmoji(false)

    // ---- 編集処理 ----

    /** 未確定を全部確定 (選択中の候補があればそれ、なければ読みのまま) */
    private fun commitAll() {
        if (selected >= 0) commitCandidateInBatch(selected)
        flushDirect()
    }

    private inline fun edit(block: () -> Unit) {
        val ic = currentInputConnection
        ic?.beginBatchEdit()
        try { block() } finally { ic?.endBatchEdit() }
    }

    private fun afterInput() {
        selected = -1
        cands = if (direct || composer.isEmpty) emptyList() else converter.candidates(composer.text)
        showComposing()
        updateUi()
    }

    private fun showComposing() {
        val ic = currentInputConnection ?: return
        val t = cands.getOrNull(selected)?.let { it.text + composer.text.substring(it.readingLen) }
            ?: composer.text
        if (t.isEmpty()) {
            if (composingShown) ic.setComposingText("", 1)
            composingShown = false
        } else {
            ic.setComposingText(t, 1)
            composingShown = true
        }
    }

    /** 未確定文字列をそのまま確定 */
    private fun flushDirect() {
        if (composer.isEmpty) return
        currentInputConnection?.commitText(composer.text, 1)
        composer.clear()
        composingShown = false
        cands = emptyList()
        selected = -1
    }

    private fun commitSelectedInBatch() = commitCandidateInBatch(selected)

    private fun commitCandidate(i: Int) = edit { commitCandidateInBatch(i); updateUi() }

    private fun commitCandidateInBatch(i: Int) {
        val c = cands.getOrNull(i) ?: return
        val typed = composer.text.substring(0, c.readingLen)
        currentInputConnection?.commitText(c.text, 1)
        composingShown = false
        // 予測候補は本来の読みで学習 (きょう で選んだ 共有 → きょうゆう)
        if (!noLearn && c.learnable && c.text != typed) converter.learn(c.reading ?: typed, c.text)
        composer.consume(c.readingLen)
        afterInput()
    }

    private fun enterAction() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** 数字モードは最後の1文字に常に効く。かな/英字はトグル中のみ */
    private fun cycleEnabled() =
        selected < 0 && if (keyboard.mode == Mode.NUM) composer.canCycle else composer.isToggling

    private fun updateUi() {
        if (!::keyboard.isInitialized) return
        keyboard.state = UiState(
            composing = !composer.isEmpty && !direct,
            toggling = composer.isToggling,
            cycleEnabled = cycleEnabled(),
            modifiable = keyboard.mode == Mode.KANA && selected < 0 &&
                composer.lastChar()?.let { KanaModifier.next(it) } != null,
        )
        candBar.set(cands, selected)
        if (candPanel.visibility == View.VISIBLE) {
            if (cands.isEmpty()) showCandPanel(false) else candPanel.set(cands, selected)
        }
    }
}
