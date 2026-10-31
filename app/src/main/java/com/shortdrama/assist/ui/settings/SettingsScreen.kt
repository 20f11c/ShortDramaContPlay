package com.shortdrama.assist.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shortdrama.assist.ui.components.IosDivider
import com.shortdrama.assist.ui.components.IosFootnote
import com.shortdrama.assist.ui.components.IosGroup
import com.shortdrama.assist.ui.components.IosPage
import com.shortdrama.assist.ui.components.IosRow
import com.shortdrama.assist.ui.components.IosSectionTitle
import com.shortdrama.assist.ui.components.IosSegmented
import com.shortdrama.assist.ui.components.IosSwitch
import com.shortdrama.assist.ui.theme.LocalTokens

/**
 * 设置页只放"偏好"。
 *
 * 权限与运行状态一律在首页（单一真相源）；规则更新策略在每个订阅的详情页
 * （不同源可以不同节奏）。这里出现任何这三类内容都是重复。
 */
@Composable
fun SettingsScreen(
    darkOverride: Int,
    onDarkOverrideChange: (Int) -> Unit,
    floatingBallEnabled: Boolean,
    floatingBallAvailable: Boolean,
    onFloatingBallChange: (Boolean) -> Unit,
    onRequestOverlay: () -> Unit,
    devLogEnabled: Boolean,
    onDevLogToggle: (Boolean) -> Unit,
    onOpenDevLog: () -> Unit,
    versionName: String,
    onAbout: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    val t = LocalTokens.current
    IosPage(title = "设置") {
        IosSectionTitle("外观")
        // iOS 的分段控件永远躺在一张白卡里，否则选中块的边界会消失在页面底色上
        IosGroup {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                IosSegmented(
                    options = listOf("跟随系统", "浅色", "深色"),
                    selectedIndex = darkOverride.coerceIn(0, 2),
                    onSelect = onDarkOverrideChange,
                )
            }
        }

        IosSectionTitle("悬浮球")
        IosGroup {
            if (floatingBallAvailable) {
                IosRow(
                    title = "显示悬浮球",
                    subtitle = "叠在红果上面，点一下回到这里",
                    chevron = false,
                    trailing = {
                        IosSwitch(
                            checked = floatingBallEnabled,
                            onCheckedChange = onFloatingBallChange,
                        )
                    },
                )
            } else {
                IosRow(
                    title = "显示悬浮球",
                    subtitle = "需要「显示在其他应用上层」权限",
                    value = "去授权",
                    valueColor = t.accent,
                    onClick = onRequestOverlay,
                )
            }
        }

        IosSectionTitle("调试")
        IosGroup {
            IosRow(
                title = "开发者日志",
                chevron = false,
                trailing = {
                    IosSwitch(checked = devLogEnabled, onCheckedChange = onDevLogToggle)
                },
            )
            IosDivider()
            IosRow(title = "查看运行日志", onClick = onOpenDevLog)
        }

        IosSectionTitle("其他")
        IosGroup {
            IosRow(title = "关于续播", value = versionName, onClick = onAbout)
            IosDivider()
            IosRow(
                title = "恢复默认设置",
                titleColor = t.danger,
                chevron = false,
                onClick = onResetDefaults,
            )
        }

        IosFootnote("权限与运行状态见首页 · 规则更新见订阅详情")
        Spacer(Modifier.height(28.dp))
    }
}
