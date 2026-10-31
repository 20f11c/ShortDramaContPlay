package com.shortdrama.assist.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.ui.theme.LocalTokens

/**
 * 共享组件。全部从 [LocalTokens] 取色，因此明暗主题自动适配。
 */

/** 顶栏：左标题、右竖点菜单。 */
@Composable
fun AppTopBar(title: String, onMenu: (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = t.textSecondary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (onMenu != null) {
            KebabMenu(onMenu)
        }
    }
}

/** 竖三点。用三个实心圆手绘，避免依赖图标包字体（emoji 在部分机型会变豆腐块）。 */
@Composable
fun KebabMenu(onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .semantics { contentDescription = "设置" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(t.textSecondary),
            )
        }
    }
}

/** 主按钮：整屏唯一的高饱和按钮。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    color: Color? = null,
    enabled: Boolean = true,
) {
    val t = LocalTokens.current
    val bg = (color ?: t.accent).let { if (enabled) it else it.copy(alpha = 0.35f) }
    val ink = if (t.isDark) Color(0xFF1A1200) else Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ink,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 幽灵按钮：次要动作。 */
@Composable
fun GhostButton(text: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, t.hairline, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = t.textSecondary, fontSize = 16.sp)
    }
}

/** 状态胶囊。颜色不作唯一载体 —— 始终带文字（色盲友好）。 */
@Composable
fun StatusPill(text: String, color: Color, soft: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(soft)
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .semantics { contentDescription = "当前状态：$text" }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(9.dp))
        Text(text = text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 环形进度。[fraction] 0..1。
 * 用弧长表达剩余量，比读数字快；中心是大数字 + 说明。
 */
@Composable
fun RingProgress(
    fraction: Float,
    centerMain: String,
    centerUnit: String?,
    centerLabel: String,
    size: Dp = 250.dp,
    ringWidth: Dp = 15.dp,
    color: Color? = null,
) {
    val t = LocalTokens.current
    val active = color ?: t.accent
    Box(
        modifier = Modifier
            .size(size)
            .semantics {
                // 颜色不能是唯一信息载体 —— 给读屏一句完整描述
                contentDescription = buildString {
                    append(centerMain)
                    if (centerUnit != null) append(centerUnit)
                    append("，")
                    append(centerLabel)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = ringWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = t.ringTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fraction > 0f) {
                drawArc(
                    color = active,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = centerMain,
                    color = t.text,
                    fontSize = 62.sp,
                    fontWeight = FontWeight.Light,
                )
                if (centerUnit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = centerUnit,
                        color = t.textSecondary,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = centerLabel,
                color = t.textTertiary,
                fontSize = 13.sp,
                letterSpacing = 3.sp,
            )
        }
    }
}

/** 卡片容器：暗色靠亮度分层，亮色靠阴影（这里用极淡的描边近似）。 */
@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = LocalTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(t.surface)
            .border(1.dp, t.hairline, RoundedCornerShape(18.dp)),
    ) { content() }
}

/** 设置项行。 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = t.text, fontSize = 16.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(text = subtitle, color = t.textTertiary, fontSize = 13.sp)
            }
        }
        if (trailing != null) {
            Text(text = trailing, color = t.textSecondary, fontSize = 14.sp)
        }
    }
}

/** 1px 分隔线。 */
@Composable
fun Hairline() {
    val t = LocalTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp)
            .height(1.dp)
            .background(t.hairline),
    )
}

/**
 * 顶部暖光。暗色是"光晕"，亮色是"暖调"。
 *
 * 精确对应设计稿的 CSS：
 * ```css
 * .glow{ position:absolute; inset:0;
 *        background:radial-gradient(122% 56% at 50% 0%, var(--glow) 0%, rgba(255,179,64,0) 64%) }
 * ```
 * 两个要点（初版实现错过）：
 *  1. 是**椭圆径向渐变**（rx = 122% 宽，ry = 56% 高，中心在顶部中央），不是线性色带；
 *  2. 淡出终点是**同色 alpha=0**（`rgba(255,179,64,0)`）而不是 `Color.Transparent` ——
 *     后者是"透明黑"，插值会在中间引入灰色浑浊。
 *
 * Compose 的 `Brush.radialGradient` 只支持正圆，这里用 `drawScope.scale` 把 Y 轴压缩成椭圆。
 */
@Composable
fun TopGlow(tint: Color? = null) {
    val t = LocalTokens.current
    val c = tint ?: t.accentSoft
    val faded = c.copy(alpha = 0f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val rx = size.width * 1.22f
        val ry = size.height * 0.56f
        val cx = size.width / 2f
        if (rx <= 0f) return@Canvas
        val sy = ry / rx
        scale(scaleX = 1f, scaleY = sy, pivot = Offset(cx, 0f)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to c,
                        0.64f to faded,
                        1f to faded,
                    ),
                    center = Offset(cx, 0f),
                    radius = rx,
                ),
                radius = rx,
                center = Offset(cx, 0f),
            )
        }
    }
}

/** 段落小标题。 */
@Composable
fun SectionLabel(text: String) {
    val t = LocalTokens.current
    Text(
        text = text,
        color = t.textTertiary,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 20.dp, top = 26.dp, bottom = 10.dp),
    )
}

/** 居中提示文案。 */
@Composable
fun Hint(text: String, color: Color? = null, align: TextAlign = TextAlign.Center) {
    val t = LocalTokens.current
    Text(
        text = text,
        color = color ?: t.textTertiary,
        fontSize = 13.sp,
        textAlign = align,
        modifier = Modifier.fillMaxWidth(),
    )
}
