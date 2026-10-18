package com.wavex.agent.feature.conversation

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.model.WebSearchResult
import com.wavex.agent.core.domain.port.SearchProvider
import com.wavex.agent.feature.conversation.ui.dismissPrivacyDialog
import com.wavex.agent.feature.conversation.ui.launchChatHost
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 对话页状态条的仪器钉子：渲染的是**真实生效态**，不是开关本身。
 *
 * 这组用例的价值在于它钉的是「界面会不会撒谎」：
 * - 模型只支持开/关时，设置页选了高档位，状态条必须写「思考 开」；
 * - 搜索源没配时，联网开关亮着，状态条必须写「联网 未配置」；
 * - 两个都关时不渲染空状态条（否则白占一行把输入框顶高）。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChatStatusBarInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        dismissPrivacyDialog(InstrumentationRegistry.getInstrumentation())
    }

    private fun provider(model: String) = Provider(
        id = "p-status", name = "状态条测试站",
        baseUrl = "https://example.com/v1", apiKey = "sk-test", model = model,
    )

    private val noopSearch = object : SearchProvider {
        override val id = "noop"
        override suspend fun search(query: String, limit: Int): WebSearchOutcome =
            WebSearchOutcome.Ok(listOf(WebSearchResult("x", 1, "t", "https://e", "s")))
    }

    /** 开/关族：选「高」也必须显示「思考 开」，不能照抄设置值 */
    @Test
    fun toggle_family_shows_on_instead_of_the_selected_rung() {
        val host = launchChatHost(
            compose = compose,
            conversationId = "conv-toggle",
            title = "开/关族",
            messages = listOf(ChatMessage(text = "hi", fromUser = true)),
            provider = provider("qwen3-max"),
        )
        compose.runOnUiThread { host.state.changeReasoningEffort("high") }
        compose.waitForIdle()

        compose.onNodeWithText("思考 开").assertIsDisplayed()
        host.finish()
    }

    /** 档位式模型：所选档位真的会下发，状态条照实写 */
    @Test
    fun tiered_model_shows_the_selected_rung() {
        val host = launchChatHost(
            compose = compose,
            conversationId = "conv-tiered",
            title = "档位式",
            messages = listOf(ChatMessage(text = "hi", fromUser = true)),
            provider = provider("gpt-5.2"),
        )
        compose.runOnUiThread { host.state.changeReasoningEffort("xhigh") }
        compose.waitForIdle()

        compose.onNodeWithText("思考 极高").assertIsDisplayed()
        host.finish()
    }

    /** 搜索源没装配：开关点亮后必须显示「联网 未配置」，而不是假装已联网 */
    @Test
    fun enabled_web_without_source_reports_not_configured() {
        val host = launchChatHost(
            compose = compose,
            conversationId = "conv-web-missing",
            title = "未配置搜索源",
            messages = listOf(ChatMessage(text = "hi", fromUser = true)),
            provider = provider("gpt-5.2"),
            searchProvider = null,
        )
        compose.runOnUiThread { host.state.setWebSearch(host.conversation.id, true) }
        compose.waitForIdle()

        compose.onNodeWithText("联网 未配置").assertIsDisplayed()
        host.finish()
    }

    /** 搜索源就绪：开关点亮后显示「联网 待命」（声明已发、模型还没搜） */
    @Test
    fun enabled_web_with_source_reports_standby() {
        val host = launchChatHost(
            compose = compose,
            conversationId = "conv-web-ready",
            title = "搜索源就绪",
            messages = listOf(ChatMessage(text = "hi", fromUser = true)),
            provider = provider("gpt-5.2"),
            searchProvider = noopSearch,
        )
        compose.runOnUiThread { host.state.setWebSearch(host.conversation.id, true) }
        compose.waitForIdle()

        compose.onNodeWithText("联网 待命").assertIsDisplayed()
        host.finish()
    }

    /** 思考默认 + 联网关闭：整条状态条不渲染（不能白占一行） */
    @Test
    fun nothing_configured_renders_no_status_bar() {
        val host = launchChatHost(
            compose = compose,
            conversationId = "conv-empty",
            title = "空状态",
            messages = listOf(ChatMessage(text = "hi", fromUser = true)),
            provider = provider("gpt-5.2"),
        )
        compose.waitForIdle()

        compose.onNodeWithText("思考 ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("联网 ", substring = true).assertDoesNotExist()
        host.finish()
    }
}
