package com.example.suboverlay

import android.content.Context
import android.content.SharedPreferences

/**
 * Penyimpanan pengaturan sederhana. Service membaca nilai ini setiap tick,
 * jadi perubahan langsung berlaku tanpa perlu restart service.
 */
object Prefs {

    private const val FILE = "subtitle_overlay_prefs"

    private const val KEY_SRT_PATH = "srt_path"
    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_AUTO_FOLLOW = "auto_follow"
    private const val KEY_FONT_SP = "font_sp"
    private const val KEY_Y_FRACTION = "y_fraction"
    private const val KEY_BG_ALPHA = "bg_alpha"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun srtPath(ctx: Context): String? = prefs(ctx).getString(KEY_SRT_PATH, null)

    fun setSrtPath(ctx: Context, path: String?) {
        prefs(ctx).edit().putString(KEY_SRT_PATH, path).apply()
    }

    fun offsetMs(ctx: Context): Long = prefs(ctx).getLong(KEY_OFFSET, 0L)

    fun setOffset(ctx: Context, value: Long) {
        prefs(ctx).edit().putLong(KEY_OFFSET, value).apply()
    }

    fun autoFollow(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_FOLLOW, true)

    fun setAutoFollow(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_FOLLOW, value).apply()
    }

    fun fontSizeSp(ctx: Context): Float = prefs(ctx).getFloat(KEY_FONT_SP, 20f)

    fun setFontSizeSp(ctx: Context, value: Float) {
        prefs(ctx).edit().putFloat(KEY_FONT_SP, value).apply()
    }

    fun yFraction(ctx: Context): Float = prefs(ctx).getFloat(KEY_Y_FRACTION, 0.74f)

    fun setYFraction(ctx: Context, value: Float) {
        prefs(ctx).edit().putFloat(KEY_Y_FRACTION, value.coerceIn(0f, 0.92f)).apply()
    }

    fun bgAlpha(ctx: Context): Int = prefs(ctx).getInt(KEY_BG_ALPHA, 150)

    fun setBgAlpha(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_BG_ALPHA, value.coerceIn(0, 255)).apply()
    }
}
