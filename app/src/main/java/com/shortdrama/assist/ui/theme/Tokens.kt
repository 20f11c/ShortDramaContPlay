package com.shortdrama.assist.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计令牌（iOS 18 风格）。
 *
 * 取值直接对齐 Apple Human Interface Guidelines 的系统色：
 * - 亮色：背景 #F2F2F7（systemGroupedBackground）、卡片 #FFFFFF、分隔线 #E5E5EA
 * - 暗色：背景 #000000、卡片 #1C1C1E（secondarySystemGroupedBackground）
 * - 强调色（tint）：systemBlue，亮 #007AFF / 暗 #0A84FF —— iOS 默认控件色
 *
 * 为什么不用橙色：橙色（systemOrange）在 iOS 里是"提示/状态"色，不是控件 tint。
 * 早期版本用琥珀色当主色，开关和选中态看着不像 iOS，已切回 systemBlue。
 *
 * 刻意不使用 Material You 动态取色 —— 品牌语义色会被系统壁纸冲掉。
 */
data class Tokens(
    val background: Color,
    /** 分组列表里卡片的底色（iOS 叫 secondarySystemGroupedBackground）。 */
    val surface: Color,
    /** 比 surface 再低一层，用于分段控件轨道、输入框底色。 */
    val surfaceSunken: Color,
    val hairline: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    /** 承载文字与描边的加深版（保证对比度）。 */
    val accentInk: Color,
    val accentSoft: Color,
    val ringTrack: Color,
    val idle: Color,
    val danger: Color,
    val dangerSoft: Color,
    val success: Color,
    val successSoft: Color,
    val elevation: Dp,
    val isDark: Boolean,
)

private fun argb(hex: Long, alpha: Float): Color = Color(hex).copy(alpha = alpha)

val DarkTokens = Tokens(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceSunken = Color(0xFF2C2C2E),
    hairline = Color(0xFF38383A),
    text = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF98989F),
    textTertiary = Color(0xFF5A5A5E),
    accent = Color(0xFF0A84FF),
    accentInk = Color(0xFF0A84FF),
    accentSoft = argb(0xFF0A84FF, 0.16f),
    ringTrack = Color(0xFF2C2C2E),
    idle = Color(0xFF3A3A3C),
    danger = Color(0xFFFF453A),
    dangerSoft = argb(0xFFFF453A, 0.16f),
    success = Color(0xFF30D158),
    successSoft = argb(0xFF30D158, 0.16f),
    elevation = 0.dp,
    isDark = true,
)

val LightTokens = Tokens(
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEFEFF4),
    hairline = Color(0xFFE5E5EA),
    text = Color(0xFF000000),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFFC7C7CC),
    accent = Color(0xFF007AFF),
    accentInk = Color(0xFF007AFF),
    accentSoft = argb(0xFF007AFF, 0.12f),
    ringTrack = Color(0xFFE5E5EA),
    idle = Color(0xFFC7C7CC),
    danger = Color(0xFFFF3B30),
    dangerSoft = argb(0xFFFF3B30, 0.12f),
    success = Color(0xFF34C759),
    successSoft = argb(0xFF34C759, 0.12f),
    elevation = 0.dp,
    isDark = false,
)

val LocalTokens = staticCompositionLocalOf { DarkTokens }
