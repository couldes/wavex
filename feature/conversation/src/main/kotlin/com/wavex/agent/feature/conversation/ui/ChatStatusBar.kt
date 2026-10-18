package com.wavex.agent.feature.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.core.domain.chat.EffectiveReasoning
import com.wavex.agent.core.domain.chat.ReasoningSupport
import com.wavex.agent.core.domain.model.EffectiveWebSearch

/**
 * 对话页的「本轮真实生效态」状态条：联网 + 思考档位。
 *
 * 存在的理由：输入栏的胶囊只表达用户意图，而用户真正会误判的是**意图与生效不一致**——
 * 开关亮着但搜索源没配、模型只支持开/关却显示成「高」、上游拒掉 tools 后静默不搜。
 * 所以这里渲染的是 [EffectiveWebSearch] / [EffectiveReasoning]（由 domain 按模型能力
 * 与上一轮真实结果推导），而不是渲染开关本身。
 *
 * 三条显示原则：
 * 1. **降级必须可见**：`已降级` 用警示色，且与开关的高亮并存 —— 用户看到「我开着，
 *    但这轮没生效」，而不是以为生效了。
 * 2. **能力差异必须可见**：开/关族显示「开」而不是「高」，模型决定型显示「模型决定」。
 * 3. **不占视线**：单行、极小字号、无点击热区（长按说明走系统无障碍文案），
 *    思考关闭且联网关闭时整条不渲染，避免空行把输入框顶高。
 */
@Composable
internal fun ChatStatusBar(
    reasoning: EffectiveReasoning,
    web: EffectiveWebSearch,
    modifier: Modifier = Modifier
) {
    // 两个都关闭且无任何轮次事实时整条不画：空状态条只会白占一行高度
    val reasoningVisible = reasoning.requested.isNotBlank() || reasoning.dropped
    val webVisible = web.enabled || web.dropped
    if (!reasoningVisible && !webVisible) return

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (reasoningVisible) {
            StatusPill(
                icon = Icons.Default.Psychology,
                // 「思考 高」/「思考 开」/「思考 模型决定」/「思考 已降级」
                text = "思考 ${reasoning.label}",
                emphasized = reasoning.applied,
                warning = reasoning.dropped,
                detail = reasoning.detail,
            )
        }
        if (webVisible) {
            StatusPill(
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
private fun StatusPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    emphasized: Boolean,
    warning: Boolean,
    detail: String,
) {
    val color = when {
        warning -> MaterialTheme.colorScheme.error
        emphasized -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = if (emphasized || warning) 0.12f else 0f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            // 长按/读屏要能问出「为什么是这个值」：explanation 走无障碍文案，
            // 不额外占屏（状态条本身刻意保持一行）
            .semantics { contentDescription = "$text：$detail" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, Modifier.size(13.dp), tint = color)
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = if (emphasized || warning) FontWeight.Medium else FontWeight.Normal,
            color = color
        )
    }
}
