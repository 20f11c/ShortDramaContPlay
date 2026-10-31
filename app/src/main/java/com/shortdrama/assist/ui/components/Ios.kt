package com.shortdrama.assist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.ui.theme.LocalTokens

/**
 * iOS 18 风格的页面骨架。
 *
 * 参照 UIKit 的分组列表（inset grouped）语义：
 * - 大标题 34pt Bold，左 20pt，右侧可放 1~2 个图标按钮
 * - 分组标题 13pt，灰色，全大写风格在中文里去掉
 * - 分组卡片圆角 16，左右各留 16pt，卡片内某一行高 48~52
 * - 行与行之间的分隔线只在左侧内缩，不贯穿整卡
 */
@Composable
fun IosPage(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    /** 底部预留：有 tab bar 的页面留 56，被 push 出来的详情页留 8。 */
    bottomPadding: Dp = 56.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.background)
            .statusBarsPadding(),
    ) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 4.dp)
                    .heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack,
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosChevron(color = t.accent, boxSize = 12.dp, stroke = 2.2.dp, flipX = true)
                    Spacer(Modifier.width(3.dp))
                    Text(text = "返回", fontSize = 17.sp, color = t.accent)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 12.dp,
                    top = if (onBack != null) 0.dp else 6.dp,
                    bottom = 14.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                color = t.text,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        if (scroll) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = bottomPadding),
                content = content,
            )
        } else {
            Column(modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}

@Composable
fun IosSectionTitle(text: String, trailing: String? = null) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = t.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(text = trailing, fontSize = 13.sp, color = t.textSecondary)
        }
    }
}

/** 一组圆角卡片，内部各行自带左右 16pt 内边距。 */
@Composable
fun IosGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(t.surface),
        content = content,
    )
}

/** 卡片内的分隔线：左侧内缩 16pt，与 iOS 一致。 */
@Composable
fun IosDivider(startInset: Dp = 16.dp) {
    val t = LocalTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(0.7.dp)
            .background(t.hairline),
    )
}

/** iOS 的细箭头，比 Material 的 KeyboardArrowRight 更像系统控件。 */
@Composable
fun IosChevron(
    color: Color? = null,
    boxSize: Dp = 13.dp,
    stroke: Dp = 1.8.dp,
    flipX: Boolean = false,
) {
    val t = LocalTokens.current
    val c = color ?: t.textTertiary
    androidx.compose.foundation.Canvas(modifier = Modifier.size(boxSize)) {
        scale(scaleX = if (flipX) -1f else 1f, scaleY = 1f, pivot = center) {
            val w = size.width
            val h = size.height
            drawLine(
                color = c,
                start = Offset(w * 0.30f, h * 0.06f),
                end = Offset(w * 0.74f, h * 0.5f),
                strokeWidth = stroke.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = c,
                start = Offset(w * 0.74f, h * 0.5f),
                end = Offset(w * 0.30f, h * 0.94f),
                strokeWidth = stroke.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 一行。
 *
 * [trailing] 与 [value] 二选一：要放开关就传 trailing，要放右侧文字就传 value。
 * 只要 [onClick] 不为空才显示箭头 —— 纯展示行（比如权限状态）也常有箭头，
 * 因为点进去就是"去修它"，所以默认显示。
 */
@Composable
fun IosRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    titleColor: Color? = null,
    chevron: Boolean = true,
    minHeight: Dp = 48.dp,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                color = titleColor ?: if (enabled) t.text else t.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.5.sp,
                    color = valueColor ?: t.textSecondary,
                    lineHeight = 16.sp,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                color = valueColor ?: t.textSecondary,
                maxLines = 1,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        if (chevron && trailing == null) {
            Spacer(Modifier.width(8.dp))
            IosChevron()
        }
    }
}

/** iOS 开关：轨道 51×31，滑块纯白带一层很淡的投影。 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
) {
    val t = LocalTokens.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = t.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = t.surfaceSunken,
            uncheckedBorderColor = t.hairline,
            disabledCheckedThumbColor = Color.White,
            disabledCheckedTrackColor = t.accent.copy(alpha = 0.35f),
            disabledUncheckedThumbColor = Color.White,
            disabledUncheckedTrackColor = t.surfaceSunken,
            disabledUncheckedBorderColor = t.hairline,
        ),
    )
}

/** iOS 分段控件：轨道 #EFEFF4，选中项白底小块。 */
@Composable
fun IosSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(t.surfaceSunken)
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) t.surface else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = t.text,
                )
            }
        }
    }
}

/** 分组下方的灰色说明，对应 iOS 的 table footer。 */
@Composable
fun IosFootnote(text: String, topPadding: Dp = 14.dp) {
    val t = LocalTokens.current
    Text(
        text = text,
        fontSize = 12.sp,
        color = t.textSecondary,
        lineHeight = 17.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = 8.dp),
    )
}

/** 标题栏右侧的图标按钮。 */
@Composable
fun IosIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    val t = LocalTokens.current
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: t.accent,
            modifier = Modifier.size(21.dp),
        )
    }
}
