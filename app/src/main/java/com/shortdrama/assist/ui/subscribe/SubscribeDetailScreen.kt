package com.shortdrama.assist.ui.subscribe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.rules.RulePackage
import com.shortdrama.assist.rules.RuleSubscription
import com.shortdrama.assist.ui.components.IosDivider
import com.shortdrama.assist.ui.components.IosFootnote
import com.shortdrama.assist.ui.components.IosGroup
import com.shortdrama.assist.ui.components.IosPage
import com.shortdrama.assist.ui.components.IosRow
import com.shortdrama.assist.ui.components.IosSectionTitle
import com.shortdrama.assist.ui.components.IosSwitch
import com.shortdrama.assist.ui.theme.LocalTokens

/**
 * 订阅详情。
 *
 * 顶部展示来源身份（官方源 / 第三方源 + 地址），中间是规则包元信息与生效条件，
 * 底部是每个订阅独立的更新策略与操作。
 */
@Composable
fun SubscribeDetailScreen(
    sub: RuleSubscription,
    pkg: RulePackage?,
    checking: Boolean,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onRollback: () -> Unit,
    onRemove: () -> Unit,
) {
    val t = LocalTokens.current
    IosPage(title = "订阅详情", onBack = onBack, bottomPadding = 12.dp) {
        Spacer(Modifier.height(2.dp))

        // —— 来源身份 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(t.surface)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sub.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    SourcePill(official = sub.url.contains("20f11c/ShortDramaContPlay"))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = prettyUrl(sub.url),
                    fontSize = 11.5.sp,
                    color = t.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IosSwitch(checked = sub.enabled, onCheckedChange = onToggle)
        }

        IosSectionTitle("规则包")
        IosGroup {
            IosRow(
                title = "版本",
                value = buildString {
                    append(pkg?.version ?: sub.version ?: "—")
                    val count = pkg?.rules?.size ?: sub.ruleCount
                    if (count > 0) append(" · $count 条")
                },
                chevron = false,
            )
            IosDivider()
            IosRow(
                title = "最低 App 版本",
                value = pkg?.minAppVersion ?: sub.minAppVersion ?: "不限",
                chevron = false,
            )
            // 只有真的带签名时才显示这一行 —— 全部源都显示「未签名」只是噪音，
            // 而官方源靠 URL 已经用胶囊标出来了。
            if (pkg?.signed == true || sub.signed) {
                IosDivider()
                IosRow(
                    title = "来源签名",
                    value = "官方签名",
                    valueColor = t.success,
                    chevron = false,
                )
            }
            IosDivider()
            IosRow(
                title = "包名匹配",
                value = pkg?.packageName ?: "—",
                chevron = false,
            )
        }

        if (pkg != null && pkg.warnings.isNotEmpty()) {
            IosFootnote(
                text = pkg.warnings.joinToString("\n") { "· $it" },
                topPadding = 10.dp,
            )
        }
        if (sub.lastError != null) {
            IosFootnote(text = "上次更新失败：${sub.lastError}", topPadding = 10.dp)
        }

        IosSectionTitle("更新与操作")
        IosGroup {
            IosRow(
                title = "自动检查更新",
                chevron = false,
                trailing = {
                    IosSwitch(checked = sub.autoUpdate, onCheckedChange = onAutoUpdateChange)
                },
            )
            IosDivider()
            IosRow(
                title = "立即检查更新",
                value = if (checking) "检查中…" else null,
                onClick = { if (!checking) onCheckNow() },
            )
            IosDivider()
            IosRow(title = "回滚到上一版本", onClick = onRollback)
            IosDivider()
            IosRow(title = "移除订阅", chevron = false, onClick = onRemove)
        }

        IosFootnote(
            text = "第三方源无需签名；规则只做声明式判据，不会执行任何远程代码。",
            topPadding = 14.dp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SourcePill(official: Boolean) {
    val t = LocalTokens.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(t.accentSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (official) "官方源" else "第三方源",
            fontSize = 11.sp,
            color = t.accentInk,
        )
    }
}

/** 长 URL 压成「仓库 › 路径」的形式，详情页不用横向滚动。 */
private fun prettyUrl(url: String): String {
    val cleaned = url
        .removePrefix("https://")
        .removePrefix("http://")
    return cleaned
        .replace("cdn.jsdelivr.net/gh/", "")
        .replace("raw.githubusercontent.com/", "")
}
