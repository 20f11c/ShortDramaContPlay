package com.shortdrama.assist.ui.subscribe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.rules.RuleSubscription
import com.shortdrama.assist.ui.components.IosIconAction
import com.shortdrama.assist.ui.components.IosPage
import com.shortdrama.assist.ui.components.IosSwitch
import com.shortdrama.assist.ui.theme.LocalTokens

/**
 * 订阅列表（iOS plain list）。
 *
 * 刻意不做卡片：列表型能一眼扫完所有源，行高 68 也够放"来源 · 条数 · 版本"。
 * 顶部右侧两个图标按钮：刷新全部、添加订阅。
 */
@Composable
fun SubscribeScreen(
    subscriptions: List<RuleSubscription>,
    checking: Boolean,
    onRefreshAll: () -> Unit,
    onAdd: () -> Unit,
    onToggle: (RuleSubscription, Boolean) -> Unit,
    onOpen: (RuleSubscription) -> Unit,
    footerText: String,
) {
    val t = LocalTokens.current
    IosPage(
        title = "订阅",
        actions = {
            IosIconAction(
                icon = Icons.Filled.Refresh,
                contentDescription = "刷新全部",
                onClick = { if (!checking) onRefreshAll() },
                tint = if (checking) t.textTertiary else t.accent,
            )
            IosIconAction(
                icon = Icons.Filled.Add,
                contentDescription = "添加订阅",
                onClick = onAdd,
            )
        },
    ) {
        Text(
            text = "规则订阅",
            fontSize = 13.sp,
            color = t.textSecondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 7.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.surface),
        ) {
            subscriptions.forEachIndexed { index, sub ->
                if (index > 0) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp)
                            .height(0.7.dp)
                            .background(t.hairline),
                    )
                }
                SubscribeRow(
                    sub = sub,
                    onToggle = { onToggle(sub, it) },
                    onOpen = { onOpen(sub) },
                )
            }
        }
        if (footerText.isNotEmpty()) {
            Text(
                text = footerText,
                fontSize = 12.sp,
                color = t.textSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SubscribeRow(
    sub: RuleSubscription,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val t = LocalTokens.current
    val failed = sub.lastError != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            )
            .heightIn(min = 68.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = sub.name, fontSize = 17.sp, color = t.text)
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitleOf(sub),
                fontSize = 12.5.sp,
                color = if (failed) t.danger else t.textSecondary,
            )
        }
        Spacer(Modifier.weight(0.05f))
        IosSwitch(checked = sub.enabled, onCheckedChange = onToggle)
    }
}

/** 副标题优先显示错误，其次显示「host · N 条 · 版本」。 */
private fun subtitleOf(sub: RuleSubscription): String {
    if (sub.lastError != null) {
        val parts = mutableListOf<String>()
        hostOf(sub.url).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        parts.add("更新失败")
        parts.add(sub.lastError)
        return parts.joinToString(" · ")
    }
    val parts = mutableListOf<String>()
    hostOf(sub.url).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    if (sub.ruleCount > 0) parts.add("${sub.ruleCount} 条")
    sub.version?.takeIf { it.isNotEmpty() }?.let { parts.add("v$it") }
    return parts.joinToString(" · ")
}

private fun hostOf(url: String): String = try {
    val host = java.net.URI(url).host ?: ""
    // cdn.jsdelivr.net 这类镜像只显示域名主干，避免一屏塞满
    if (host.endsWith("jsdelivr.net")) "jsdelivr" else host
} catch (_: Throwable) {
    ""
}
