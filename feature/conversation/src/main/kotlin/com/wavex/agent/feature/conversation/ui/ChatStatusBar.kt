package com.wavex.agent.feature.conversation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.core.domain.chat.EffectiveReasoning
import com.wavex.agent.core.domain.model.EffectiveWebSearch

/**
 * 顶栏副标题：`模型名 · 思考 X · 联网 Y`。
 *
 * 为什么挂在顶栏而不是输入框上方：这是「当前这一轮跑在什么配置上」的属性，
 * 和模型名同属一个语义层级（换模型就换档位含义），放在一起用户才不用两处对照。
 *
 * 渲染的是**生效态**而不是开关：输入栏的胶囊只表达意图，用户最容易误判的恰恰是
 * 意图与生效不一致——开关亮着但搜索源没配、模型只支持开/关却以为选了高档、
 * 上游拒掉 tools 后静默不搜。所以这里读 [EffectiveReasoning] / [EffectiveWebSearch]，
 * 由 domain 按模型能力与上一轮真实结果推导。
 *
 * 三条显示原则：
 * 1. **降级必须可见**：`已降级` 用警示色，且与胶囊的高亮并存 —— 用户看到「我开着，
 *    但这轮没生效」，而不是以为生效了。
 * 2. **能力差异必须可见**：开/关族显示「开」而不是「高」，模型决定型显示「模型决定」。
 * 3. **不占视线**：单行、11sp、与模型名同一行；全关时只留模型名（不留分隔符）。
 */
@Composable
fun ChatStatusLine(
    model: String,
    reasoning: EffectiveReasoning,
    web: EffectiveWebSearch,
    modifier: Modifier = Modifier
) {
    // 全关时只显示模型名：分隔符也要跟着消失，否则会留一个悬空的「·」
    val reasoningVisible = reasoning.requested.isNotBlank() || reasoning.dropped
    val webVisible = web.enabled || web.dropped

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            model,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight(fill = false)：模型名优先被压缩，状态位永远不被挤掉——
            // 用户看状态的目的就是「模型名 + 状态」同时可见
            modifier = Modifier.weight(1f, fill = false)
        )
        if (reasoningVisible) {
            StatusSegment(
                icon = Icons.Default.Psychology,
                text = "思考 ${reasoning.label}",
                emphasized = reasoning.applied,
                warning = reasoning.dropped,
                detail = reasoning.detail,
            )
        }
        if (webVisible) {
            StatusSegment(
                icon = Icons.Default.TravelExplore,
                text = "联网 ${web.label}",
                emphasized = web.active,
                warning = web.dropped || (web.enabled && !web.sourceReady),
                detail = web.detail,
            )
        }
    }
}

@Composable
private fun StatusSegment(
    icon: ImageVector,
    text: String,
    emphasized: Boolean,
    warning: Boolean,
    detail: String,
) {
    val color: Color = when {
        warning -> MaterialTheme.colorScheme.error
        emphasized -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        // 读屏/无障碍要能问出「为什么是这个值」：整段文案带上解释，
        // 界面本身刻意只留一行（顶栏空间宝贵）
        modifier = Modifier.semantics { contentDescription = "$text：$detail" }
    ) {
        Icon(icon, contentDescription = null, Modifier.size(12.dp), tint = color)
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = if (emphasized || warning) FontWeight.Medium else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
