package com.shortdrama.assist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.ui.theme.LocalTokens

/** 三个 tab：首页（状态 + 快捷开关）、订阅（规则包）、设置（纯偏好）。 */
enum class AppTab(val label: String) {
    HOME("首页"),
    SUBSCRIBE("订阅"),
    SETTINGS("设置"),
}

@Composable
fun BottomTabs(current: AppTab, onSelect: (AppTab) -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.surface)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.7.dp)
                .background(t.hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabEntry(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Home,
                label = AppTab.HOME.label,
                active = current == AppTab.HOME,
                onClick = { onSelect(AppTab.HOME) },
            )
            TabEntry(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.List,
                label = AppTab.SUBSCRIBE.label,
                active = current == AppTab.SUBSCRIBE,
                onClick = { onSelect(AppTab.SUBSCRIBE) },
            )
            TabEntry(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Settings,
                label = AppTab.SETTINGS.label,
                active = current == AppTab.SETTINGS,
                onClick = { onSelect(AppTab.SETTINGS) },
            )
        }
    }
}

@Composable
private fun TabEntry(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    val color = if (active) t.accent else t.textSecondary
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(23.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = color,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
