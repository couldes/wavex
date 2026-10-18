package com.wavex.agent.feature.conversation

import androidx.lifecycle.viewModelScope
import com.wavex.agent.core.domain.chat.ContentLoader
import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.ApiProtocol
import com.wavex.agent.core.domain.model.AttachmentWriteResult
import com.wavex.agent.core.domain.model.AuthDialect
import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ConnectionReport
import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.SessionStorageStatus
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.WebSearchOutcome
import com.wavex.agent.core.domain.model.WebSearchResult
import com.wavex.agent.core.domain.port.ConversationRepository
import com.wavex.agent.core.domain.port.GeneratedFileWriter
import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.core.domain.port.ProviderSettingsRepository
import com.wavex.agent.core.domain.port.SearchProvider
import com.wavex.agent.feature.conversation.viewmodel.ConversationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * 联网搜索回路接线（计划 Task 9）：
 * - 模型要搜 → App 搜 → 结果回填 → 第二轮拿正文；
 * - 工具调用必须经 snapshotOf 搬进落盘快照（漏搬 → 重启即丢）。
 *
 * 用真实时间模型（单线程 Main 执行器 + 真实 IO），与 ConversationViewModelTest 同构。
 */
class ToolLoopIntegrationTest {

    private val mainExecutor = Executors.newSingleThreadExecutor()
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    private class FakeRepository : ConversationRepository {
        val snapshots = mutableListOf<AgentConversationData>()
        override suspend fun loadAll(): List<ConversationSnapshot> = emptyList()
        override suspend fun sync(snapshots: List<AgentConversationData>, suppressedMessageIds: Map<String, String>) {
            this.snapshots.clear()
            this.snapshots.addAll(snapshots)
        }
        override suspend fun importSessions(snapshots: List<AgentConversationData>): Int = snapshots.size
        override suspend fun refreshStorage(): SessionStorageStatus = SessionStorageStatus(false, "", false)
    }

    private class FakeSettings(model: String = "test-model") : ProviderSettingsRepository {
        private val provider = Provider(
            id = "p1", name = "测试站", baseUrl = "https://example.com", apiKey = "sk-test", model = model
        )
        override fun loadProviders(): List<Provider> = listOf(provider)
        override fun saveProviders(providers: List<Provider>) {}
        override fun loadCurrentProviderId(): String? = "p1"
        override fun loadCurrentProvider(): Provider = provider
        override fun saveCurrentProviderId(id: String?) {}
        override fun loadFetchedModels(): Map<String, List<String>> = emptyMap()
        override fun saveFetchedModels(models: Map<String, List<String>>) {}
        override fun loadThemeChoice(): String = ""
        override fun saveThemeChoice(value: String) {}
        override fun loadReasoningEffort(): String = ""
        override fun saveReasoningEffort(value: String) {}
    }

    /** 第一轮吐工具调用，第二轮吐正文；记录每轮收到的历史 */
    private class ScriptedModel : ModelService {
        val histories = mutableListOf<List<ChatRequestMessage>>()
        var calls = 0

        override suspend fun streamChat(
            provider: Provider,
            history: List<ChatRequestMessage>,
            reasoningEffort: String?,
            webSearch: Boolean,
            onDelta: (String, String) -> Unit,
            onImage: (String) -> Unit,
            onToolCalls: (List<ToolCall>) -> Unit,
            onThinking: (String, String) -> Unit,
        ) {
            histories += history.toList()
            calls++
            if (calls == 1) {
                // 带思考：Anthropic 协议要求这条 assistant 的 thinking 块在回填轮原样回传。
                // 思考走两条通道，与 HttpApiSession 的真实行为一致：
                // onDelta 的第二个参数喂 UI 的「已思考 N 字」，onThinking 喂回路
                onDelta("", "用户要价格，先搜索")
                onThinking("用户要价格，先搜索", "sig-1")
                onToolCalls(
                    listOf(ToolCall("call_1", "web_search", """{"query":"btc"}""", "btc"))
                )
            } else {
                onDelta("Bitcoin is 108432 USD [cite:a1b2c3]", "")
            }
        }

        override suspend fun generateTitle(provider: Provider, transcript: String): String? = null
        override suspend fun fetchModels(provider: Provider): List<String>? = emptyList()
        override suspend fun probe(provider: Provider): ConnectionReport =
            ConnectionReport(true, ApiProtocol.OPENAI, "", AuthDialect.BEARER, 1, 1L, null)
    }

    private class FakeSearch : SearchProvider {
        override val id = "fake"
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, limit: Int): WebSearchOutcome {
            queries += query
            return WebSearchOutcome.Ok(
                listOf(WebSearchResult("a1b2c3", 1, "BTC price", "https://e/btc", "108432 USD"))
            )
        }
    }

    private val repo = FakeRepository()
    private val model = ScriptedModel()
    private val search = FakeSearch()
    private lateinit var vm: ConversationViewModel

    /** 用指定模型名重建被测 VM（重建前先停掉旧的 viewModelScope，不留并发协程） */
    private fun rebuildVm(modelName: String = "test-model") {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        vm = ConversationViewModel(
            repo, FakeSettings(modelName), model,
            ContentLoader { null },
            GeneratedFileWriter { _, _, _ -> AttachmentWriteResult.TooLarge },
            search,
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        rebuildVm()
    }

    @After
    fun tearDown() {
        // 先取消 VM 的 scope，再等它的协程真正停手，最后才摘 Main：
        // viewModelScope 跑在 mainDispatcher 上，直接 resetMain 会与仍在执行的
        // 存储轮询协程竞争（kotlinx-coroutines-test 会抛
        // "Dispatchers.Main is used concurrently with setting it"）
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        mainDispatcher.close()
        Dispatchers.resetMain()
    }

    private fun awaitUntil(desc: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("awaitUntil 超时: $desc")
            Thread.sleep(10)
        }
    }

    @Test
    fun `loop sends two rounds and backfills tool result`() {
        val conv = vm.currentConversation
        conv.appendMessage(ChatMessage(text = "btc price?", fromUser = true))
        vm.setWebSearch(vm.currentConversationId, true)

        vm.startGeneration(conv)

        awaitUntil("两轮都发出") { model.calls >= 2 }
        awaitUntil("生成结束") { !vm.isGeneratingIn(conv.id) }

        assertEquals(2, model.calls)
        assertEquals(listOf("btc"), search.queries)
        // 第二轮历史里必须有配对的 tool 结果，否则上游会 400
        val toolMsg = model.histories[1].firstOrNull { it.role == "tool" }
        assertTrue("第二轮历史应含 tool 消息", toolMsg != null)
        assertEquals("call_1", toolMsg!!.toolCallId)
        assertTrue(toolMsg.text.contains("a1b2c3"))
        // 正文进了气泡
        val reply = conv.messages.last { !it.fromUser }
        assertTrue(reply.text.contains("108432"))
    }

    /**
     * 回填轮的 thinking 配对（真机 Anthropic 400 抓出来的第二条约束）：
     * 历史里出现 tool_use 时，同一条 assistant 消息的 thinking 块必须一起回传。
     */
    @Test
    fun `backfill round echoes the assistant thinking and signature`() {
        val conv = vm.currentConversation
        conv.appendMessage(ChatMessage(text = "btc price?", fromUser = true))
        vm.setWebSearch(vm.currentConversationId, true)

        vm.startGeneration(conv)
        awaitUntil("两轮都发出") { model.calls >= 2 }
        awaitUntil("生成结束") { !vm.isGeneratingIn(conv.id) }

        val assistant = model.histories[1].first { it.role == "assistant" }
        assertEquals("用户要价格，先搜索", assistant.thinking)
        assertEquals("sig-1", assistant.thinkingSignature)
        // 思考只属于 assistant，不该渗进工具结果消息
        assertTrue(model.histories[1].filter { it.role == "tool" }.all { it.thinking == null })
    }

    /**
     * 追问轮同样要带上思考：落盘重建的历史走的是 HistoryBuilder，
     * 和回路内那条是两条独立代码路径，漏一条就会在用户「接着问」时 400。
     */
    @Test
    fun `follow up turn echoes thinking on the message that declares tool calls`() {
        val conv = vm.currentConversation
        conv.appendMessage(ChatMessage(text = "btc price?", fromUser = true))
        vm.setWebSearch(vm.currentConversationId, true)
        vm.startGeneration(conv)
        awaitUntil("首轮结束") { !vm.isGeneratingIn(conv.id) }

        model.calls = 0
        model.histories.clear()
        conv.appendMessage(ChatMessage(text = "and eth?", fromUser = true))
        vm.startGeneration(conv)
        awaitUntil("追问结束") { !vm.isGeneratingIn(conv.id) }

        val history = model.histories.first()
        val declaring = history.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals("用户要价格，先搜索", declaring.thinking)
        assertEquals("sig-1", declaring.thinkingSignature)
    }

    @Test
    fun `second turn after a search sends a well paired history`() {
        // 生产里工具结果只活在 ToolLoop 的临时历史中，落盘的消息树只有
        // assistant(toolCalls)+正文。第二轮必须把合成结果插在声明它的 assistant
        // 消息之后——追加到末尾会被上游 400（这条路径此前完全没有测试覆盖）
        val conv = vm.currentConversation
        conv.appendMessage(ChatMessage(text = "btc price?", fromUser = true))
        vm.setWebSearch(vm.currentConversationId, true)
        vm.startGeneration(conv)
        awaitUntil("首轮结束") { !vm.isGeneratingIn(conv.id) }

        model.calls = 0
        model.histories.clear()
        conv.appendMessage(ChatMessage(text = "and eth?", fromUser = true))
        vm.startGeneration(conv)
        awaitUntil("第二轮结束") { !vm.isGeneratingIn(conv.id) }

        val history = model.histories.first()
        val roles = history.map { it.role }
        // 每个 tool_use 后面必须紧跟配对的 tool 结果
        history.forEachIndexed { i, m ->
            m.toolCalls.forEach { c ->
                val next = history.getOrNull(i + 1)
                assertTrue(
                    "tool_use ${c.id} 后应紧跟 tool 结果，实际 roles=$roles",
                    next != null && next.role == "tool" && next.toolCallId == c.id,
                )
            }
        }
    }

    /** 联网开关是会话级的：A 会话开着不该污染 B 会话 */
    @Test
    fun `web search toggle is scoped per conversation`() {
        val a = vm.currentConversation
        vm.setWebSearch(a.id, true)
        assertTrue(vm.webSearchFor(a.id))
        assertFalse(vm.webSearchFor("another-conversation"))
        assertFalse(vm.effectiveWebSearchFor("another-conversation").enabled)
    }

    /**
     * 状态条的联网计数：开关亮着只是意图，用户要看的是「这一轮到底搜了几次」。
     * 计数来自 ToolLoop 的 onToolFinished，而不是来自开关本身。
     */
    @Test
    fun `effective web search reports the real search count`() {
        val conv = vm.currentConversation
        conv.appendMessage(ChatMessage(text = "btc price?", fromUser = true))
        vm.setWebSearch(conv.id, true)

        // 生成前：声明会发出去，但一次都还没搜
        val before = vm.effectiveWebSearchFor(conv.id)
        assertTrue("搜索源已装配，应声明工具", before.toolDeclared)
        assertEquals(0, before.searches)
        assertEquals("待命", before.label)

        vm.startGeneration(conv)
        awaitUntil("两轮都发出") { model.calls >= 2 }
        awaitUntil("生成结束") { !vm.isGeneratingIn(conv.id) }

        val after = vm.effectiveWebSearchFor(conv.id)
        assertEquals(1, after.searches)
        assertTrue(after.allSucceeded)
        assertTrue(after.active)
        assertEquals("已搜 1 次", after.label)
    }

    /** 新的一轮开始时上一轮的搜索计数必须归零，否则状态条会累计到离谱的数字 */
    @Test
    fun `search count resets when a new round starts`() {
        val conv = vm.currentConversation
        conv.appendMessage(ChatMessage(text = "btc price?", fromUser = true))
        vm.setWebSearch(conv.id, true)
        vm.startGeneration(conv)
        awaitUntil("首轮结束") { !vm.isGeneratingIn(conv.id) }
        assertEquals(1, vm.effectiveWebSearchFor(conv.id).searches)

        // 关掉联网再发一轮：这一轮不该有任何搜索，计数必须回到 0 且状态是「关闭」
        vm.setWebSearch(conv.id, false)
        conv.appendMessage(ChatMessage(text = "thanks", fromUser = true))
        vm.startGeneration(conv)
        awaitUntil("第二轮结束") { !vm.isGeneratingIn(conv.id) }

        val w = vm.effectiveWebSearchFor(conv.id)
        assertEquals(0, w.searches)
        assertEquals("关闭", w.label)
    }

    /**
     * 思考档位的生效态必须按**模型能力**解释，而不是照抄设置值。
     * 这里把服务商换成 qwen3（开/关族）：用户选「高」，状态条要显示「开」。
     */
    @Test
    fun `effective reasoning degrades to a toggle for on off models`() {
        // 换成开/关族模型（qwen3）：设置页选「高」，实际生效的只有「开」
        rebuildVm("qwen3-max")
        vm.changeReasoningEffort("high")
        val e = vm.effectiveReasoningFor(vm.currentConversationId)
        assertEquals("high", e.requested)
        assertEquals("on", e.level)
        assertEquals("开", e.label)
    }

    @Test
    fun `snapshot carries tool calls to the repository`() {
        val conv = vm.currentConversation
        conv.appendMessage(ChatMessage(text = "btc price?", fromUser = true))
        vm.setWebSearch(vm.currentConversationId, true)

        vm.startGeneration(conv)
        awaitUntil("两轮都发出") { model.calls >= 2 }
        awaitUntil("生成结束") { !vm.isGeneratingIn(conv.id) }
        // 等一轮持久化轮询落盘
        awaitUntil("快照落盘") {
            repo.snapshots.any { s -> s.nodes.values.any { it.toolCalls.isNotEmpty() } }
        }

        val withCalls = repo.snapshots
            .flatMap { it.nodes.values }
            .filter { it.toolCalls.isNotEmpty() }
        assertTrue("落盘快照里应带工具调用", withCalls.isNotEmpty())
        assertEquals("web_search", withCalls.first().toolCalls.first().name)
    }
}
