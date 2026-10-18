package com.wavex.agent.feature.conversation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.core.domain.chat.EffectiveReasoning
import com.wavex.agent.core.domain.model.ApiProtocol
import com.wavex.agent.core.domain.model.EffectiveWebSearch
import com.wavex.agent.core.ui.AgentTheme
import com.wavex.agent.feature.conversation.ui.ChatStatusLine
import com.wavex.agent.feature.conversation.ui.dismissPrivacyDialog
import com.wavex.agent.feature.conversation.ui.launchContentHost
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 顶栏副标题（模型名 + 生效态）的仪器钉子：渲染的是**真实生效态**，不是开关本身。
 *
 * 这组用例钉的是「界面会不会撒谎」：
 * - 模型只支持开/关时，设置页选了高档位，副标题必须写「思考 开」；
 * - 搜索源没配时，联网开关亮着，副标题必须写「联网 未配置」；
 * - 两个都关时只剩模型名（不留悬空的分隔符）。
 *
 * 直接组合 [ChatStatusLine] 而不是起整个 ChatScreen：状态行是纯展示组件，
 * 入参就是推导结果；用 ChatScreen 反而要装配 VM、仓库、搜索源，测的还是别的东西。
 *
 * 宿主走 [launchContentHost] 而不是 createComposeRule()：后者在 ZUI 上会被系统隐私
 * 弹窗抢焦点，setContent 后 waitForIdle 永不返回（实测单用例卡满 344s 超时）。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChatStatusLineInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        dismissPrivacyDialog(InstrumentationRegistry.getInstrumentation())
    }

    private fun show(reasoning: EffectiveReasoning, web: EffectiveWebSearch, model: String = "gpt-5.2") {
        launchContentHost(compose) {
            ChatStatusLine(
                model = model,
                reasoning = reasoning,
                web = web,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    private val webOff = EffectiveWebSearch.preview(enabled = false, sourceReady = true)

    /** 开/关族：选「高」也必须显示「思考 开」，不能照抄设置值 */
    @Test
    fun toggle_family_shows_on_instead_of_the_selected_rung() {
        show(
            reasoning = EffectiveReasoning.preview("high", "qwen3-max", ApiProtocol.OPENAI),
            web = webOff,
            model = "qwen3-max",
        )
        compose.onNodeWithText("思考 开").assertIsDisplayed()
    }

    /** 档位式模型：所选档位真的会下发，副标题照实写 */
    @Test
    fun tiered_model_shows_the_selected_rung() {
        show(
            reasoning = EffectiveReasoning.preview("xhigh", "gpt-5.2", ApiProtocol.OPENAI),
            web = webOff,
        )
        compose.onNodeWithText("思考 极高").assertIsDisplayed()
    }

    /** 模型决定型：档位对它无效，必须说「模型决定」而不是显示所选档位 */
    @Test
    fun model_decided_family_reports_model_decides() {
        show(
            reasoning = EffectiveReasoning.preview("xhigh", "deepseek-chat", ApiProtocol.OPENAI),
            web = webOff,
            model = "deepseek-chat",
        )
        compose.onNodeWithText("思考 模型决定").assertIsDisplayed()
    }

    /** 搜索源没装配：开关点亮后必须显示「联网 未配置」，而不是假装已联网 */
    @Test
    fun enabled_web_without_source_reports_not_configured() {
        show(
            reasoning = EffectiveReasoning.preview("", "gpt-5.2", ApiProtocol.OPENAI),
            web = EffectiveWebSearch.preview(enabled = true, sourceReady = false),
        )
        compose.onNodeWithText("联网 未配置").assertIsDisplayed()
    }

    /** 搜索源就绪：开关点亮后显示「联网 待命」（声明已发、模型还没搜） */
    @Test
    fun enabled_web_with_source_reports_standby() {
        show(
            reasoning = EffectiveReasoning.preview("", "gpt-5.2", ApiProtocol.OPENAI),
            web = EffectiveWebSearch.preview(enabled = true, sourceReady = true),
        )
        compose.onNodeWithText("联网 待命").assertIsDisplayed()
    }

    /** 真的搜过：显示次数，而不是「开」 */
    @Test
    fun searches_are_reported_with_a_count() {
        show(
            reasoning = EffectiveReasoning.preview("", "gpt-5.2", ApiProtocol.OPENAI),
            web = EffectiveWebSearch.preview(enabled = true, sourceReady = true).copy(searches = 2),
        )
        compose.onNodeWithText("联网 已搜 2 次").assertIsDisplayed()
    }

    /** 思考默认 + 联网关闭：只剩模型名，不留悬空的分隔符 */
    @Test
    fun nothing_configured_renders_only_the_model_name() {
        show(
            reasoning = EffectiveReasoning.preview("", "gpt-5.2", ApiProtocol.OPENAI),
            web = webOff,
        )
        compose.onNodeWithText("gpt-5.2").assertIsDisplayed()
        compose.onNodeWithText("思考 ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("联网 ", substring = true).assertDoesNotExist()
    }

    /** 被上游拒掉：开关仍开着，但副标题必须说清本轮没生效 */
    @Test
    fun dropped_round_reports_degraded() {
        show(
            reasoning = EffectiveReasoning.preview("high", "gpt-5.2", ApiProtocol.OPENAI),
            web = EffectiveWebSearch.preview(enabled = true, sourceReady = true)
                .copy(dropped = true, toolDeclared = false),
        )
        compose.onNodeWithText("联网 已降级").assertIsDisplayed()
    }
}
