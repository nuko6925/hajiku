package io.github.nuko6925.flickkb

import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.os.Bundle
import android.util.Size
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.Toast
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
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
    private lateinit var autofillBar: AutofillBarView

    // ---- パスワード欄・自動入力 ----
    /** 文字のパスワード欄 (QWERTY + スクショ禁止) */
    private var passwordField = false
    /** 🔑 を出す欄 (パスワード・メール・ユーザー名らしい欄) */
    private var loginField = false
    private var inlineViews: List<View> = emptyList()
    private var inlineGen = 0
    /** QWERTY のシフト: 0 = オフ, 1 = 次の1文字, 2 = ロック */
    private var shift = 0
    private var lastShiftTap = 0L
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
        autofillBar = AutofillBarView(this) { openPasswordManager() }.apply { visibility = View.GONE }
        // 候補バーと自動入力バーは同じ場所を切り替えて使う
        val barSlot = FrameLayout(this).apply {
            addView(candBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(autofillBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        emojiPanel = EmojiPanelView(this, this).apply { visibility = View.GONE }
        kbContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false   // 上段フリックのポップアップを候補バー上に描くため
            clipToPadding = false
            addView(barSlot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * dp).toInt()))
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
        autofillBar.theme = t
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
        passwordField = cls == InputType.TYPE_CLASS_TEXT && password
        loginField = passwordField || variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        shift = 0
        keyboard.mode = when {
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME -> Mode.NUM
            passwordField -> Mode.QWERTY  // iOS と同じくパスワード欄は QWERTY
            cls == InputType.TYPE_CLASS_TEXT && alphaVariation -> Mode.ALPHA
            else -> Mode.KANA
        }
        // パスワード入力中はキーボードをスクショ・画面録画に写さない
        window?.window?.let { w ->
            if (password) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        direct = password || cls != InputType.TYPE_CLASS_TEXT || info.inputType == InputType.TYPE_NULL
        noLearn = password || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        updateUi()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        inlineGen++
        setInline(emptyList())
        currentInputConnection?.finishComposingText()
        reset()
    }

    // ---- 自動入力 (インライン候補) ----

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        val dp = resources.displayMetrics.density
        val t = KbTheme(isDark())
        // iOS と同じく枠なしの文字だけ
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(ViewStyle.Builder()
                .setBackgroundColor(Color.TRANSPARENT)
                .setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                .build())
            .setTitleStyle(TextViewStyle.Builder().setTextColor(t.text).setTextSize(16f).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(t.disabled).setTextSize(12f).build())
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val h = (36 * dp).toInt()
        val spec = InlinePresentationSpec.Builder(Size((60 * dp).toInt(), h), Size((280 * dp).toInt(), h))
            .setStyle(styles).build()
        return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(4).build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val list = response.inlineSuggestions
        val gen = ++inlineGen
        if (list.isEmpty()) {
            setInline(emptyList())
            return true
        }
        val views = arrayOfNulls<View>(list.size)
        var remaining = list.size
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        list.forEachIndexed { i, s ->
            s.inflate(this, size, mainExecutor) { v ->
                views[i] = v
                if (--remaining == 0 && gen == inlineGen) setInline(views.filterNotNull())
            }
        }
        return true
    }

    private fun setInline(views: List<View>) {
        inlineViews = views
        if (::autofillBar.isInitialized) {
            autofillBar.setSuggestions(views)
            updateUi()
        }
    }

    /** 🔑: 今選ばれている自動入力サービスのアプリを開く */
    private fun openPasswordManager() {
        val pkg = android.provider.Settings.Secure.getString(contentResolver, "autofill_service")
            ?.substringBefore('/')
        val intent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            Toast.makeText(this, "自動入力サービスが設定されていません（設定 → パスワードとアカウント）", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun reset() {
        handler.removeCallbacks(toggleTimeout)
        composeStart = -1
        selfSel.clear()
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
        if (candidatesStart >= 0) composeStart = candidatesStart
        if (composer.isEmpty) return
        val inside = candidatesStart >= 0 && newSelStart == newSelEnd &&
            newSelStart in candidatesStart..candidatesEnd
        if (!inside) {
            // 未確定の外へカーソルが動いた (よそをタップ等) → 未確定を確定扱いで終える
            currentInputConnection?.finishComposingText()
            reset()
            updateUi()
            return
        }
        // 自分で動かした位置の通知は無視 (遅れて届いた古い通知で上書きしないため)
        val now = System.currentTimeMillis()
        selfSel.removeAll { now - it.second > 600 }
        if (selfSel.removeAll { it.first == newSelStart }) return
        // 未確定内をタップした → その位置にカーソルを合わせる
        if (selected < 0 && candidatesEnd - candidatesStart == composer.length) {
            val pos = newSelStart - candidatesStart
            if (pos != composer.cursor) {
                composer.setCursor(pos)
                updateUi()
            }
        }
    }

    /** 未確定文字列の先頭の絶対位置 (エディタからの通知で更新。不明なら -1) */
    private var composeStart = -1
    /** 自分で setSelection した位置と時刻 */
    private val selfSel = ArrayDeque<Pair<Int, Long>>()

    // ---- KeyboardView.Listener ----

    override fun onCharTap(key: Key) = edit {
        if (keyboard.mode.isQwerty) {
            // QWERTY は1打1文字。シフトは1文字だけ (ロック中は継続)
            if (selected >= 0) commitSelectedInBatch()
            flushDirect()
            val s = if (shift > 0) key.cycle[0].uppercase() else key.cycle[0]
            if (shift == 1) shift = 0
            composer.insert(s)
            if (direct) flushDirect()
            afterInput()
            return@edit
        }
        if (selected >= 0) commitSelectedInBatch()
        if (direct && composer.toggleKey !== key) flushDirect()
        composer.tap(key)
        afterInput()
        // かな・英字は一定時間内の連打だけ循環 (iOS: 1.5秒)。過ぎたら同じキーで次の文字
        handler.removeCallbacks(toggleTimeout)
        if (keyboard.mode != Mode.NUM && key.toggle) handler.postDelayed(toggleTimeout, TOGGLE_TIMEOUT_MS)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val toggleTimeout = Runnable {
        if (composer.isToggling) {
            composer.endToggle()
            updateUi()
        }
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
                } else if (selected < 0 && !composer.cursorAtEnd) {
                    // 未確定文字列内でカーソルを右へ
                    composer.moveCursor(+1)
                    showComposing()
                }
                updateUi()
            }
            KeyType.TOGGLE_BACK -> edit { if (cycleEnabled()) { composer.stepByArrow(-1); afterInput() } }
            KeyType.TOGGLE_FWD -> edit { if (cycleEnabled()) { composer.stepByArrow(+1); afterInput() } }
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
            KeyType.SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == 0 && now - lastShiftTap < 350 -> 2  // ダブルタップでロック
                    shift == 0 -> 1
                    shift == 1 && now - lastShiftTap < 350 -> 2
                    else -> 0
                }
                lastShiftTap = now
                updateUi()
            }
            KeyType.CHAR -> {}
        }
    }

    override fun onFunctionLongPress(key: Key): Boolean = when (key.type) {
        // 未確定中は未確定文字列の中でカーソルを動かす (候補選択中なら読みに戻す)
        KeyType.SPACE -> {
            if (!composer.isEmpty && !direct && selected >= 0) edit {
                selected = -1
                showComposing()
                updateUi()
            } else if (direct) edit { commitAll(); updateUi() }
            true
        }
        KeyType.EMOJI -> {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            true
        }
        else -> false
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        if (!composer.isEmpty && !direct) {
            if (dx != 0) edit {
                composer.moveCursor(dx)
                showComposing()
                updateUi()
            }
            return
        }
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
                Mode.QWERTY -> "ABC"
                Mode.QWERTY_NUM, Mode.QWERTY_SYM -> "123"
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
            composeStart = -1
        } else {
            ic.setComposingText(t, 1)
            composingShown = true
            // カーソルが末尾以外ならエディタ上のカーソルも合わせる
            if (selected < 0 && !composer.cursorAtEnd && composeStart >= 0) {
                val pos = composeStart + composer.cursor
                ic.setSelection(pos, pos)
                selfSel.addLast(pos to System.currentTimeMillis())
            }
        }
    }

    /** 未確定文字列をそのまま確定 */
    private fun flushDirect() {
        if (composer.isEmpty) return
        currentInputConnection?.commitText(composer.text, 1)
        composer.clear()
        composingShown = false
        composeStart = -1
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
        // 残りの未確定は確定した文字列の直後から始まる
        if (composeStart >= 0) composeStart += c.text.length
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
        selected < 0 && if (keyboard.mode == Mode.NUM) composer.canCycle else composer.canArrowCycle

    private fun updateUi() {
        if (!::keyboard.isInitialized) return
        keyboard.state = UiState(
            composing = !composer.isEmpty && !direct,
            toggling = composer.isToggling,
            cursorNext = composer.isToggling || (!composer.isEmpty && selected < 0 && !composer.cursorAtEnd),
            cycleEnabled = cycleEnabled(),
            modifiable = keyboard.mode == Mode.KANA && selected < 0 &&
                composer.lastChar()?.let { KanaModifier.next(it) } != null,
            shift = shift,
        )
        candBar.set(cands, selected)
        // 何も入力していない時は自動入力バー (インライン候補 + 🔑)
        val showAutofill = cands.isEmpty() && (inlineViews.isNotEmpty() || loginField)
        autofillBar.visibility = if (showAutofill) View.VISIBLE else View.GONE
        candBar.visibility = if (showAutofill) View.INVISIBLE else View.VISIBLE
        if (candPanel.visibility == View.VISIBLE) {
            if (cands.isEmpty()) showCandPanel(false) else candPanel.set(cands, selected)
        }
    }

    companion object {
        private const val TOGGLE_TIMEOUT_MS = 750L
    }
}
