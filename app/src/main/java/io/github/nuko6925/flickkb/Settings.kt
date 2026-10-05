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
}
