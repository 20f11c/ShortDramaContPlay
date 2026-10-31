package com.shortdrama.assist.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.shortdrama.assist.DevLog
import com.shortdrama.assist.DevLogOverlay
import com.shortdrama.assist.ui.components.CardBox
import com.shortdrama.assist.ui.components.Hairline
import com.shortdrama.assist.ui.components.SectionLabel
import com.shortdrama.assist.ui.theme.LocalTokens
import kotlinx.coroutines.delay

/**
 * 开发者模式：控制内存日志记录，并查看/复制/分享日志。
 *
 * 日志只保留在 App 进程内存中，最多 [DevLog.MAX_ENTRIES] 条，便于用户自助排查 OCR/Shizuku 问题。
 */
@Composable
fun DevLogScreen(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val t = LocalTokens.current
    val context = LocalContext.current
    var entries by remember { mutableStateOf(DevLog.snapshot()) }
    val listState = rememberLazyListState()
    val overlayGranted = Settings.canDrawOverlays(context)

    LaunchedEffect(enabled) {
        while (true) {
            entries = DevLog.snapshot()
            delay(1_000)
        }
    }

    LaunchedEffect(enabled, overlayGranted) {
        if (enabled && overlayGranted) DevLogOverlay.sync(context)
    }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(entries.lastIndex)
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
                    text = "开发者模式",
                    color = t.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.padding(horizontal = 22.dp))
            }

            CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "记录运行日志", color = t.text, fontSize = 16.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = if (enabled) {
                                    "正在记录 OCR / Shizuku 运行事件"
                                } else {
                                    "开启后可在 App 内查看和导出"
                                },
                                color = t.textTertiary,
                                fontSize = 13.sp,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = onToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = t.accent,
                                checkedTrackColor = t.accent.copy(alpha = 0.35f),
                                uncheckedThumbColor = t.textTertiary,
                                uncheckedTrackColor = t.hairline,
                            ),
                        )
                    }
                    Hairline()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LogAction("复制", onCopy, Modifier.weight(1f))
                        HairlineVertical()
                        LogAction("分享", onShare, Modifier.weight(1f))
                        HairlineVertical()
                        LogAction("清空", onClear, Modifier.weight(1f))
                    }
                    Hairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${entries.size} / ${DevLog.MAX_ENTRIES} 条",
                            color = t.textTertiary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "仅保存在本机内存",
                            color = t.textTertiary,
                            fontSize = 12.sp,
                        )
                    }
                    Hairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!overlayGranted) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } else {
                                    DevLogOverlay.sync(context)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "悬浮日志", color = t.text, fontSize = 14.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "屏幕最顶层透明显示，点击条展开",
                                color = t.textTertiary,
                                fontSize = 12.sp,
                            )
                        }
                        Text(
                            text = if (overlayGranted) "已开启" else "去授权",
                            color = t.accent,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            SectionLabel("最近日志")
            CardBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                if (entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (enabled) {
                                "等待日志…启动 OCR 后会自动记录"
                            } else {
                                "开发者日志未开启"
                            },
                            color = t.textTertiary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        items(entries) { entry ->
                            SelectionContainer {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                    Text(
                                        text = "${entry.level}/${entry.tag}",
                                        color = when (entry.level) {
                                            'E' -> t.danger
                                            'W' -> t.accent
                                            else -> t.textTertiary
                                        },
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        text = entry.message,
                                        color = t.textSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun LogAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = t.accent, fontSize = 14.sp)
    }
}

@Composable
private fun HairlineVertical() {
    val t = LocalTokens.current
    Box(
        modifier = Modifier
            .padding(vertical = 10.dp)
            .width(1.dp)
            .height(22.dp)
            .background(t.hairline),
    )
}
