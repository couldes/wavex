package com.wavex.agent.feature.catalog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.feature.catalog.CatalogViewModel.ThemeChoice

/**
 * 设置页小型共用控件：分区标题、分段控件、主题预览卡。
 * 从 SettingsScreen.kt 拆出（Task 8 Step 3）。
 */

@Composable
internal fun SettingSectionTitle(text: String) {
    Text(text, Modifier.padding(top = 18.dp, bottom = 8.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SegmentedControl(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun RowScope.ThemePreviewCard(
    label: String,
    choice: ThemeChoice,
    selected: ThemeChoice,
    darkPreview: Boolean,
    onSelected: (ThemeChoice) -> Unit
) {
    // 预览用的固定配色（不跟随动态色，保证每个选项看起来稳定）
    val bg = if (darkPreview) Color(0xFF141414) else Color(0xFFF7F7F7)
    val bar = if (darkPreview) Color(0xFF232323) else Color(0xFFFDFDFD)
    val bubbleOut = if (darkPreview) Color(0xFF3A3A3A) else Color(0xFFE8E8E8)
    val accent = if (darkPreview) Color(0xFF9EC6FF) else Color(0xFF2E6BE6)
    val text = if (darkPreview) Color(0xFFBBBBBB) else Color(0xFF666666)
    val isSelected = choice == selected

    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelected(choice) }
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            Column(Modifier.fillMaxSize().padding(6.dp)) {
                // 迷你顶栏
                Row(
                    Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(3.dp)).background(bar),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.padding(start = 2.dp).size(4.dp).background(text, CircleShape))
                    Box(Modifier.padding(start = 3.dp).size(width = 22.dp, height = 3.dp).clip(RoundedCornerShape(1.5.dp)).background(text.copy(alpha = 0.5f)))
                }
                Spacer(Modifier.height(5.dp))
                // 两条消息气泡（收/发）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.size(width = 42.dp, height = 9.dp).clip(RoundedCornerShape(4.dp)).background(accent))
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(width = 52.dp, height = 9.dp).clip(RoundedCornerShape(4.dp)).background(bubbleOut))
                Spacer(Modifier.weight(1f))
                // 底部 Tab
                Row(
                    Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(3.dp)).background(bar),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Box(Modifier.size(4.dp).background(accent, CircleShape))
                    Box(Modifier.size(4.dp).background(text.copy(alpha = 0.6f), CircleShape))
                    Box(Modifier.size(4.dp).background(text.copy(alpha = 0.6f), CircleShape))
                }
            }
            if (isSelected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "已选择", modifier = Modifier.size(10.dp), tint = Color.White)
                }
            }
        }
        Text(
            label,
            fontSize = 11.sp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
        )
    }
}

