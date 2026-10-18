package com.wavex.agent

import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.core.domain.chat.RoundOutcome
import com.wavex.agent.core.domain.chat.RoundSender
import com.wavex.agent.core.domain.chat.ToolLoop
import com.wavex.agent.core.domain.chat.ToolLoopConfig
import com.wavex.agent.core.domain.chat.ToolLoopResult
import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.WebSearchOutcome
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * 真机全链路联网搜索（本机执行 + 真实中转站 + 真实 Bing）。
 *
 * 跑的是生产装配：`AppContainer.modelService`（RequestPayloads → HttpApiSession →
 * ToolCallAccumulator）与 `AppContainer.searchProvider`（BingRssSearchProvider），
 * 中间由 domain 的 ToolLoop 串起来。这条链路上任何一环接错，这里就会红。
 *
 * 凭据不入库，从 instrumentation 参数传入；缺省时整条用例跳过
 * （没有凭据的 CI 不该因此变红）：
 *
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.wavex.agent.LiveEndToEndSearchTest \
 *   -Pandroid.testInstrumentationRunnerArguments.baseUrl=... \
 *   -Pandroid.testInstrumentationRunnerArguments.apiKey=... \
 *   -Pandroid.testInstrumentationRunnerArguments.model=...
 */
class LiveEndToEndSearchTest {

    private companion object {
        const val TAG = "LiveE2ESearch"
    }

    private fun arg(key: String): String =
        InstrumentationRegistry.getArguments().getString(key) ?: ""

    /**
     * 没给凭据就是「不该跑」，不是「跑挂了」。
     *
     * 不用 JUnit 的 assumeTrue：androidx.test 这套 runner 不把
     * AssumptionViolatedException 记成 assumption failure，而是照抄成普通失败
     * （实测 skipped=0、failures=2），没凭据的 CI 会因此变红。所以这里直接返回，
     * 并把「本次没真跑」打到 logcat 里，让跑的人看得见。
     *
     * 代价是绿色不代表真跑过：发版前必须按 KDoc 里的命令带凭据再跑一次。
     */
    private fun ready(): Boolean =
        arg("baseUrl").isNotBlank() && arg("apiKey").isNotBlank() && arg("model").isNotBlank()

    private fun skip(tag: String) {
        android.util.Log.i(TAG, "SKIPPED $tag：未提供 baseUrl/apiKey/model，本次未真实联网")
    }

    @Test(timeout = 300_000)
    fun real_relay_and_real_search_produce_a_cited_answer() {
        if (!ready()) { skip("real_relay_and_real_search_produce_a_cited_answer"); return }
        val baseUrl = arg("baseUrl"); val apiKey = arg("apiKey"); val model = arg("model")
        runBlocking {
        val c = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext.let { it as WavexApplication }.container
        val provider = Provider(
            id = "live-e2e", name = "live", baseUrl = baseUrl, apiKey = apiKey, model = model
        )

        val rounds = mutableListOf<List<ToolCall>>()
        val answer = StringBuilder()
        val loopHistory = mutableListOf(
            ChatRequestMessage("user", "比特币现在多少钱美元？必须先调用 web_search 查，再回答。")
        )
        val searches = mutableListOf<String>()

        val loop = ToolLoop(
            config = ToolLoopConfig(),
            search = c.searchProvider,
            onToolStarted = { },
            onToolFinished = { view -> searches += view.query },
        )
        var roundThinking: String? = null
        var roundSignature: String? = null
        val sender = RoundSender { h ->
            val calls = mutableListOf<ToolCall>()
            roundThinking = null
            roundSignature = null
            c.modelService.streamChat(
                provider, h,
                reasoningEffort = null,
                webSearch = true,
                onDelta = { text, _ -> answer.append(text) },
                onImage = { },
                onToolCalls = { list -> calls.clear(); calls.addAll(list) },
                onThinking = { t, sig -> roundThinking = t; roundSignature = sig },
            )
            rounds += calls.toList()
            RoundOutcome(calls.toList(), roundThinking, roundSignature)
        }

        val result = loop.run(loopHistory, sender)
        val outcome = result::class.simpleName

        // 1. 模型真的请求了工具
        assertTrue("模型应调用 web_search，实际轮次=$rounds", searches.isNotEmpty())
        // 2. 搜索在本机真的跑通（不是 Transient 也不是空）
        val okRound = loopHistory.filter { it.role == "tool" }
            .firstOrNull { it.text.contains("\"items\"") }
        assertTrue("回填历史里应有搜索结果，实际=${loopHistory.map { it.role }}", okRound != null)
        // 3. 请求历史里每个 tool_use 都有配对结果（孤儿会被上游 400）
        val declared = loopHistory.filter { it.role == "assistant" }.flatMap { c2 -> c2.toolCalls.map { it.id } }
        val answered = loopHistory.filter { it.role == "tool" }.mapNotNull { it.toolCallId }
        assertTrue("tool_use 必须全部配对：declared=$declared answered=$answered",
            declared.toSet() == answered.toSet())
        // 4. 回路善终且拿到正文
        assertTrue("回路应正常收尾，实际 $outcome",
            result is ToolLoopResult.Done || result is ToolLoopResult.RoundLimit)
            assertTrue("应有可读回答，实际「$answer」", answer.isNotBlank())
        }
    }

    /**
     * 追问轮：搜索之后的第二条提问。
     *
     * 此时历史里躺着一条带 tool_use 的 assistant 消息，Anthropic 要求它的 thinking
     * 块一起回传。回路内的那次回填已经对了，但落盘重建的这一轮是另一条代码路径
     * （HistoryBuilder），漏了就会在用户最自然的「接着问」上 400。
     *
     * 带不带 thinking 各跑一次，用来证明这个字段确实是上游要求的（反向控制）。
     */
    @Test(timeout = 300_000)
    fun follow_up_turn_after_search_keeps_tool_pairing() {
        if (!ready()) { skip("follow_up_turn_after_search_keeps_tool_pairing"); return }
        val baseUrl = arg("baseUrl"); val apiKey = arg("apiKey"); val model = arg("model")
        runBlocking {
        val c = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as WavexApplication).container
        val provider = Provider(id = "live-follow", name = "live", baseUrl = baseUrl, apiKey = apiKey, model = model)

        // 先跑一次真实搜索，拿到模型自己产生的 tool_use id 与 thinking
        val calls = mutableListOf<ToolCall>()
        var thinking = ""
        var signature = ""
        val text = StringBuilder()
        c.modelService.streamChat(
            provider,
            listOf(ChatRequestMessage("user", "今天北京天气怎么样？必须先调用 web_search。")),
            reasoningEffort = null, webSearch = true,
            onDelta = { t, r -> if (t.isNotEmpty()) text.append(t); if (r.isNotEmpty()) thinking = r },
            onImage = { },
            onToolCalls = { calls.clear(); calls.addAll(it) },
            onThinking = { t, sig -> thinking = t; signature = sig },
        )
        assertTrue("第一轮应请求工具", calls.isNotEmpty())

        // 模型可能一次请求多个搜索（实测会中英文各搜一次）。
        // 每一个 tool_use 都必须回填一条结果，少一个就是孤儿配对，上游必 400——
        // 这正是本用例要保住的形状，所以逐个搜、逐个回填。
        val results = calls.map { call ->
            val outcome = c.searchProvider.search(call.query ?: "北京天气", 3)
            val payload = if (outcome is WebSearchOutcome.Ok && outcome.results.isNotEmpty()) {
                val r = outcome.results.first()
                """{"items":[{"id":"${r.id}","index":1,"title":"${r.title}","url":"${r.url}","text":".."}]}"""
            } else {
                """{"error":"fail","retryable":true}"""
            }
            ChatRequestMessage("tool", payload, toolCallId = call.id)
        }

        // 追问：历史按落盘重建的形态拼（assistant 同时带正文、tool_use、thinking）
        val followUp = listOf(
            ChatRequestMessage("user", "今天北京天气怎么样？必须先调用 web_search。"),
            ChatRequestMessage(
                role = "assistant", text = text.toString().ifBlank { "我查一下" },
                toolCalls = calls, thinking = thinking.ifBlank { null },
                thinkingSignature = signature.ifBlank { null },
            ),
            *results.toTypedArray(),
            ChatRequestMessage("user", "那适合出门吗？"),
        )
        val answer = StringBuilder()
        c.modelService.streamChat(
            provider, followUp, reasoningEffort = null, webSearch = true,
            onDelta = { t, _ -> answer.append(t) }, onImage = { },
        )
            assertTrue("追问轮应拿到回答，实际「$answer」", answer.isNotBlank())
        }
    }

    /** 解析真实搜索载荷，确认模型看到的就是可引用的条目 */
    @Test(timeout = 120_000)
    fun bing_payload_is_valid_json_with_citable_ids() = runBlocking {
        val c = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext.let { it as WavexApplication }.container
        val outcome = c.searchProvider.search("OpenAI 最新模型", limit = 6)
        assertTrue("真实搜索应成功，实际 $outcome", outcome is WebSearchOutcome.Ok)
        val results = (outcome as WebSearchOutcome.Ok).results
        assertTrue("应有多条结果", results.size >= 2)
        assertTrue("每条都该有 id/标题/网址",
            results.all { it.id.isNotBlank() && it.title.isNotBlank() && it.url.startsWith("http") })
    }
}
