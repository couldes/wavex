package com.wavex.agent.feature.conversation.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.ToolResultView
import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.model.WebSearchResult
import com.wavex.agent.core.ui.AgentTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 工具卡片与引用角标的渲染钉子（spec §7.1 / §7.2）。
 * 无设备时这些用例待补（AGENTS.md §2：提交 body 记「仪器测试待补」）。
 */
class ToolCallCardInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun call(query: String) =
        ToolCall("call_1", "web_search", """{"query":"$query"}""", query)

    private fun results(vararg ids: String) = WebSearchOutcome.Ok(
        ids.mapIndexed { i, id ->
            WebSearchResult(id, i + 1, "Title $id", "https://$id.example", "Body $id")
        }
    )

    @Test
    fun searching_shows_progress_label() {
        compose.setContent {
            AgentTheme { ToolCallCard(query = "btc", result = null) }
        }
        compose.onNodeWithText("搜索中…", substring = true).assertIsDisplayed()
    }

    @Test
    fun success_shows_count_and_expands_to_titles() {
        compose.setContent {
            AgentTheme {
                ToolCallCard(
                    query = "btc",
                    result = ToolResultView("call_1", "btc", results("a1b2c3", "d4e5f6")),
                )
            }
        }
        compose.onNodeWithText("2 条结果", substring = true).assertIsDisplayed()
        // 折叠态不显示结果标题
        assertTrue(compose.onAllNodesWithTextCount("Title a1b2c3") == 0)
        compose.onNodeWithText("搜索「btc」", substring = true).performClick()
        compose.onNodeWithText("Title a1b2c3", substring = true).assertIsDisplayed()
    }

    @Test
    fun failure_shows_reason() {
        compose.setContent {
            AgentTheme {
                ToolCallCard(
                    query = "btc",
                    result = ToolResultView("call_1", "btc", WebSearchOutcome.Transient("timeout")),
                )
            }
        }
        compose.onNodeWithText("搜索失败", substring = true).assertIsDisplayed()
        compose.onNodeWithText("timeout", substring = true).assertIsDisplayed()
    }

    @Test
    fun citation_marker_renders_as_numbered_link() {
        val message = com.wavex.agent.core.domain.model.ChatMessage(
            text = "Bitcoin is 108432 USD [cite:a1b2c3]",
            fromUser = false,
            toolCalls = listOf(call("btc")),
            toolResults = listOf(ToolResultView("call_1", "btc", results("a1b2c3"))),
        )
        compose.setContent {
            AgentTheme { BubbleContent(message, streaming = false, onTap = {}) }
        }
        // 原始标记被替换成编号（不再是 [cite:…]）
        compose.onNodeWithText("[1]", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("[cite:") == 0)
    }

    @Test
    fun unknown_citation_is_preserved() {
        val message = com.wavex.agent.core.domain.model.ChatMessage(
            text = "See [cite:deadbeef]",
            fromUser = false,
            toolCalls = listOf(call("btc")),
            toolResults = listOf(ToolResultView("call_1", "btc", results("a1b2c3"))),
        )
        compose.setContent {
            AgentTheme { BubbleContent(message, streaming = false, onTap = {}) }
        }
        compose.onNodeWithText("[cite:deadbeef]", substring = true).assertIsDisplayed()
    }
}

/** 统计匹配节点数（0 表示不存在），避免依赖 assertDoesNotExist 的语义差异 */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(
    text: String
): Int = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
