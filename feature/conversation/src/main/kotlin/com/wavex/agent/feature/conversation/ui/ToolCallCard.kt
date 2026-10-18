package com.wavex.agent.feature.conversation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.core.domain.model.ToolResultView
import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.model.WebSearchResult

/**
 * 联网搜索工具卡片（spec §7.1）：折叠态一行摘要，展开态结果列表。
 *
 * 默认折叠：搜索结果占的竖向空间远超正文，而用户多数时候只关心正文。
 * 折叠态必须自带足够信息（关键词 + 条数/失败），否则用户还得展开才知道发生了什么。
 */
@Composable
internal fun ToolCallCard(
    query: String,
    result: ToolResultView?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val outcome = result?.outcome
    val searching = result == null
    val results = (outcome as? WebSearchOutcome.Ok)?.results.orEmpty()
    val failed = outcome != null && outcome !is WebSearchOutcome.Ok
    val label = when {
        searching -> "搜索中…"
        failed -> "搜索失败"
        else -> "${results.size} 条结果"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
            .fillMaxWidth()
            // 只有真的有结果可看时才让整行可点：失败态展开也没内容
            .then(if (results.isNotEmpty()) Modifier.clickable { expanded = !expanded } else Modifier)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.6.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = if (failed) Icons.Default.ErrorOutline else Icons.Default.TravelExplore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (query.isBlank()) "联网搜索" else "搜索「$query」",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                if (results.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起搜索结果" else "展开搜索结果",
                        modifier = Modifier
                            .size(15.dp)
                            .rotate(if (expanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            if (failed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (outcome) {
                        is WebSearchOutcome.Transient -> "网络或搜索源暂时不可用（${outcome.reason}）"
                        is WebSearchOutcome.Permanent -> outcome.reason
                        else -> ""
                    },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    results.forEachIndexed { i, r ->
                        if (i > 0) Spacer(Modifier.height(7.dp))
                        SearchResultRow(
                            index = i + 1,
                            result = r,
                            // RSS 的 link 可能是 mailto:/ftp: 等没有处理器的 scheme，
                            // openUri 会抛 IllegalArgumentException 直接崩在点击里
                            onOpen = {
                                r.url.takeIf { it.isNotBlank() }?.let { url ->
                                    runCatching { uriHandler.openUri(url) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(index: Int, result: WebSearchResult, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (result.url.isNotBlank()) Modifier.clickable(onClick = onOpen) else Modifier)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "$index.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = result.title.ifBlank { result.url },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result.text.isNotBlank()) {
                    Text(
                        text = result.text,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
