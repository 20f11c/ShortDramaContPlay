package com.shortdrama.assist

import android.content.Context

/** 用户可调设置。 */
object AppSettings {

    private const val PREFS = "sd_settings"

    private const val KEY_DARK = "dark_mode_override"
    private const val KEY_OCR_MODE = "shizuku_ocr_mode"
    private const val KEY_A11Y_MODE = "accessibility_mode"
    private const val KEY_AD_SKIP = "ad_skip_enabled"
    private const val KEY_AUTO_CONTINUE = "auto_continue_enabled"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // === 自动化模式 ===
    
    /** 无障碍模式（推荐）：使用无障碍服务检测片尾和广告。 */
    fun isA11yMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_A11Y_MODE, true)

    fun setA11yMode(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_A11Y_MODE, value).apply()
    }

    /** Shizuku OCR 模式（备选）：截图 + OCR 判定。 */
    fun isOcrMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_OCR_MODE, false)

    fun setOcrMode(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_OCR_MODE, value).apply()
    }

    // === 功能开关 ===
    
    /** 广告自动跳过 */
    fun isAdSkipEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AD_SKIP, true)

    fun setAdSkipEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AD_SKIP, value).apply()
    }

    /** 片尾自动续播 */
    fun isAutoContinueEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_CONTINUE, true)

    fun setAutoContinueEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_CONTINUE, value).apply()
    }

    // === 外观 ===

    /** 0 = 跟随系统（默认），1 = 强制亮色，2 = 强制暗色。 */
    fun darkModeOverride(ctx: Context): Int = prefs(ctx).getInt(KEY_DARK, 0)

    fun setDarkModeOverride(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_DARK, value.coerceIn(0, 2)).apply()
    }
}
