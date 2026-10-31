package com.shortdrama.assist.ui.settings

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.AppSettings
import com.shortdrama.assist.accessibility.A11yState
import com.shortdrama.assist.accessibility.AutoPlayAccessibilityService
import com.shortdrama.assist.ui.components.CardBox
import com.shortdrama.assist.ui.components.GhostButton
import com.shortdrama.assist.ui.components.Hairline
import com.shortdrama.assist.ui.components.PrimaryButton
import com.shortdrama.assist.ui.components.SectionLabel
import com.shortdrama.assist.ui.components.SettingsRow
import com.shortdrama.assist.ui.theme.LocalTokens
import kotlinx.coroutines.delay

private fun ignoringBatteryA11y(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

/**
 * 无障碍模式（主方案）。
 *
 * 这里只负责：开权限、开关功能、看状态。真正的节点树检测与手势
 * 在 [AutoPlayAccessibilityService] 里。
 */
@Composable
fun A11yScreen(
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val t = LocalTokens.current

    var enabledInSettings by remember { mutableStateOf(AutoPlayAccessibilityService.isEnabledInSettings(ctx)) }
    var connected by remember { mutableStateOf(A11yState.connected) }
    var autoContinue by remember { mutableStateOf(AppSettings.isAutoContinueEnabled(ctx)) }
    var adSkip by remember { mutableStateOf(AppSettings.isAdSkipEnabled(ctx)) }
    var status by remember { mutableStateOf(A11yState.status) }
    var lastAction by remember { mutableStateOf(A11yState.lastAction) }
    var foreground by remember { mutableStateOf(A11yState.foregroundPackage) }
    var lastText by remember { mutableStateOf(A11yState.lastText) }
    var nodeCount by remember { mutableStateOf(A11yState.lastNodeCount) }
    var batteryExempt by remember { mutableStateOf(ignoringBatteryA11y(ctx)) }

    LaunchedEffect(Unit) {
        while (true) {
            enabledInSettings = AutoPlayAccessibilityService.isEnabledInSettings(ctx)
            connected = A11yState.connected
            status = A11yState.status
            lastAction = A11yState.lastAction
            foreground = A11yState.foregroundPackage
            lastText = A11yState.lastText
            nodeCount = A11yState.lastNodeCount
            batteryExempt = ignoringBatteryA11y(ctx)
            delay(1_000)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(t.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "‹ 返回", color = t.textSecondary, fontSize = 16.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "无障碍模式",
                    color = t.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.padding(horizontal = 22.dp))
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                SectionLabel("权限")

                CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        SettingsRow(
                            title = "无障碍服务",
                            subtitle = when {
                                connected -> "已开启并正在运行"
                                enabledInSettings -> "已勾选，等待系统连接"
                                else -> "未开启。没有它一切都不会自动执行"
                            },
                            trailing = when {
                                connected -> "运行中 ›"
                                enabledInSettings -> "连接中 ›"
                                else -> "去开启 ›"
                            },
                            onClick = { AutoPlayAccessibilityService.openSettings(ctx) },
                        )
                        Hairline()
                        SettingsRow(
                            title = "后台运行",
                            subtitle = if (batteryExempt) {
                                "系统不会冻结本应用"
                            } else {
                                "部分机型会冻结后台，导致收不到界面变化事件"
                            },
                            trailing = if (batteryExempt) "已允许" else "去设置 ›",
                            onClick = {
                                if (!batteryExempt) {
                                    val intent = android.content.Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:${ctx.packageName}"),
                                    )
                                    runCatching { ctx.startActivity(intent) }.onFailure {
                                        runCatching {
                                            ctx.startActivity(
                                                android.content.Intent(
                                                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }

                SectionLabel("功能")

                CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        SettingsRow(
                            title = "自动续播",
                            subtitle = "检测到片尾提示后自动上滑下一集",
                            trailing = if (autoContinue) "已开启" else "已关闭",
                            onClick = {
                                val next = !autoContinue
                                AppSettings.setAutoContinueEnabled(ctx, next)
                                autoContinue = next
                            },
                        )
                        Hairline()
                        SettingsRow(
                            title = "广告跳过",
                            subtitle = "识别开屏/信息流/贴片广告并上滑或关掉",
                            trailing = if (adSkip) "已开启" else "已关闭",
                            onClick = {
                                val next = !adSkip
                                AppSettings.setAdSkipEnabled(ctx, next)
                                adSkip = next
                            },
                        )
                    }
                }

                SectionLabel("运行状态")

                CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        SettingsRow(
                            title = "检测结果",
                            subtitle = status.ifEmpty { "尚未开始检测" },
                            trailing = if (nodeCount > 0) "$nodeCount 节点" else "—",
                        )
                        Hairline()
                        SettingsRow(
                            title = "当前前台",
                            subtitle = foreground.ifEmpty { "等待服务上报" },
                            trailing = if (connected) "运行中" else "未运行",
                        )
                        if (lastAction.isNotEmpty()) {
                            Hairline()
                            SettingsRow(
                                title = "最近动作",
                                subtitle = lastAction,
                                trailing = "已执行",
                            )
                        }
                    }
                }

                if (lastText.isNotEmpty()) {
                    SectionLabel("页面文本")
                    CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = lastText,
                                color = t.textTertiary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (!enabledInSettings) {
                        PrimaryButton(
                            text = "去开启无障碍服务",
                            onClick = { AutoPlayAccessibilityService.openSettings(ctx) },
                        )
                    } else {
                        GhostButton(
                            text = "立即检测一次",
                            onClick = {
                                AutoPlayAccessibilityService.instance?.scanOnce { msg ->
                                    status = msg
                                } ?: run { status = "服务尚未连接" }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "为什么之前不行",
                            color = t.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "无障碍配置里必须带 flagIncludeNotImportantViews。" +
                                "广告浮层被 SDK 标记为“对无障碍不重要”，不带这个 flag 时" +
                                "在节点树里完全不存在，于是看起来像“无障碍读不到广告”。",
                            color = t.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "另外静止画面不会产生任何无障碍事件，所以还需要 3 秒一次的看门狗轮询兜底。",
                            color = t.textTertiary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
