package io.github.nuko6925.flickkb

import android.content.Context

object Settings {
    private const val PREFS = "settings"
    private const val FULLWIDTH_SPACE = "fullwidth_space"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** かなモードの空白を全角にするか (既定: 半角) */
    fun fullwidthSpace(ctx: Context) = prefs(ctx).getBoolean(FULLWIDTH_SPACE, false)

    fun setFullwidthSpace(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(FULLWIDTH_SPACE, v).apply()

    // ---- キーボードの背景 (KbBackground) ----

    fun bgMode(ctx: Context) = prefs(ctx).getInt("bg_mode", KbBackground.DEFAULT)
    fun setBgMode(ctx: Context, v: Int) = prefs(ctx).edit().putInt("bg_mode", v).apply()

    fun bgColor(ctx: Context) = prefs(ctx).getInt("bg_color", 0xFF1C3A5E.toInt())
    fun setBgColor(ctx: Context, v: Int) = prefs(ctx).edit().putInt("bg_color", v).apply()

    /** 画像の上に重ねる幕の濃さ (0〜80%) */
    fun bgScrim(ctx: Context) = prefs(ctx).getInt("bg_scrim", 25)
    fun setBgScrim(ctx: Context, v: Int) = prefs(ctx).edit().putInt("bg_scrim", v.coerceIn(0, 80)).apply()

    /** 背景を設定した時のキーの不透明度 (0〜100、-1 = 自動) */
    fun keyOpacity(ctx: Context) = prefs(ctx).getInt("key_opacity", -1)
    fun setKeyOpacity(ctx: Context, v: Int) = prefs(ctx).edit().putInt("key_opacity", v).apply()

    /** 文字色: 0 = 自動, 1 = 白, 2 = 黒, 3 = 指定 (textColor) */
    fun textMode(ctx: Context) = prefs(ctx).getInt("text_mode", 0)
    fun setTextMode(ctx: Context, v: Int) = prefs(ctx).edit().putInt("text_mode", v).apply()
    fun textColor(ctx: Context) = prefs(ctx).getInt("text_color", 0xFFFFFFFF.toInt())
    fun setTextColor(ctx: Context, v: Int) = prefs(ctx).edit().putInt("text_color", v).apply()

    /** 見た目の設定をすべて既定に (背景画像のファイルは残す) */
    fun resetStyle(ctx: Context) = prefs(ctx).edit()
        .remove("bg_mode").remove("bg_color").remove("bg_scrim")
        .remove("key_opacity").remove("text_mode").remove("text_color").apply()

    /** 背景画像の平均の明るさ (0〜1)。配色の自動選択に使う */
    fun bgImageLum(ctx: Context) = prefs(ctx).getFloat("bg_image_lum", 0.5f)
    fun setBgImageLum(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("bg_image_lum", v).apply()
}
