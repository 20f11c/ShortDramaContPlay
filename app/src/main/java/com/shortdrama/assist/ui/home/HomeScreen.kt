package com.shortdrama.assist.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.ui.components.IosDivider
import com.shortdrama.assist.ui.components.IosGroup
import com.shortdrama.assist.ui.components.IosPage
import com.shortdrama.assist.ui.components.IosRow
import com.shortdrama.assist.ui.components.IosSectionTitle
import com.shortdrama.assist.ui.components.IosSwitch
import com.shortdrama.assist.ui.components.rememberAppIcon
import com.shortdrama.assist.ui.theme.LocalTokens

/** 运行状态里的一行：客观事实，用户改不了，点进去是去修它。 */
data class PermissionRow(
    val title: String,
    val value: String,
    val granted: Boolean,
    val onClick: () -> Unit,
    /** true 表示这是「正在生效」的状态（绿色），false 表示只是「已允许」（灰色）。 */
    val running: Boolean = false,
)

/** 续播统计里的一个应用。 */
data class StatItem(
    val label: String,
    /** 目标 App 的包名，用来取真实桌面图标。 */
    val packageName: String,
    /** 取不到图标时的兜底字，1 个字最好看。 */
    val short: String,
    val count: Int,
    val color: Color,
)

/**
 * 首页。
 *
 * 三块语义分开：
 * 1. 快捷开关 —— 用户偏好，随时可改
 * 2. 运行状态 —— 系统授权结果，只能去系统里改
 * 3. 续播统计 —— 已经发生的事实
 */
@Composable
fun HomeScreen(
    running: Boolean,
    headline: String,
    subtitle: String,
    autoContinueEnabled: Boolean,
    adSkipEnabled: Boolean,
    onAutoContinueChange: (Boolean) -> Unit,
    onAdSkipChange: (Boolean) -> Unit,
    permissions: List<PermissionRow>,
    stats: List<StatItem>,
    statsFootnote: String,
) {
    val t = LocalTokens.current
    IosPage(title = "首页", bottomPadding = 72.dp) {
        Spacer(Modifier.height(4.dp))

        // —— 运行状态卡 ——
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(t.surface)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (running) t.success else t.idle),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = headline,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = t.textSecondary,
                    )
                }
            }
        }

        IosSectionTitle("快捷开关")
        IosGroup {
            IosRow(
                title = "自动续播",
                subtitle = "片尾出现提示时自动上滑下一集",
                chevron = false,
                minHeight = 54.dp,
                trailing = {
                    IosSwitch(checked = autoContinueEnabled, onCheckedChange = onAutoContinueChange)
                },
            )
            IosDivider()
            IosRow(
                title = "广告跳过",
                subtitle = "识别开屏 / 插屏 / 信息流广告",
                chevron = false,
                minHeight = 54.dp,
                trailing = {
                    IosSwitch(checked = adSkipEnabled, onCheckedChange = onAdSkipChange)
                },
            )
        }

        IosSectionTitle("运行状态")
        IosGroup {
            permissions.forEachIndexed { index, row ->
                if (index > 0) IosDivider()
                IosRow(
                    title = row.title,
                    value = row.value,
                    valueColor = when {
                        !row.granted -> t.danger
                        row.running -> t.success
                        else -> t.textSecondary
                    },
                    onClick = row.onClick,
                )
            }
        }

        IosSectionTitle("续播统计", trailing = "本周")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(t.surface)
                .padding(vertical = 16.dp),
        ) {
            Column {
                if (stats.isEmpty()) {
                    // 新装或还没续播过：给一句人话，别留一个空卡
                    Text(
                        text = "还没有记录，播完一集就会出现在这里",
                        fontSize = 13.sp,
                        color = t.textTertiary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        // 只有一两个应用时居中会让卡片看起来是"空了一半"，
                        // 少于四个就从左边排起，四个才均分。
                        horizontalArrangement =
                            if (stats.size >= 4) Arrangement.SpaceEvenly else Arrangement.Start,
                    ) {
                        stats.forEach { item ->
                            StatCell(item)
                        }
                    }
                }
                if (statsFootnote.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = statsFootnote,
                        fontSize = 12.sp,
                        color = t.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun StatCell(item: StatItem) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier.width(74.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIconTile(
            packageName = item.packageName,
            short = item.short,
            fallbackColor = item.color,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "${item.count} 次",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = t.text,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = item.label,
            fontSize = 10.sp,
            color = t.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 40dp 圆角图标块：优先画目标 App 的真实图标，拿不到才退回「纯色块 + 一个字」。
 *
 * 用 `rememberAppIcon` 而不是在这里直接调 PackageManager —— 它内部按包名缓存并在 IO 线程解析，
 * 不会被首页每秒一次的 tick 反复触发。
 */
@Composable
private fun AppIconTile(
    packageName: String,
    short: String,
    fallbackColor: Color,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { 40.dp.roundToPx() }
    val icon = rememberAppIcon(packageName, sizePx)

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (icon == null) fallbackColor else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Text(
                text = short,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}
