package com.wavex.agent.feature.conversation

import androidx.lifecycle.viewModelScope
import com.wavex.agent.core.domain.chat.ContentLoader
import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.ApiProtocol
import com.wavex.agent.core.domain.model.AttachmentWriteResult
import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ConnectionReport
import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.SessionStorageStatus
import com.wavex.agent.core.domain.port.ConversationRepository
import com.wavex.agent.core.domain.port.GeneratedFileWriter
import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.core.domain.port.ProviderSettingsRepository
import com.wavex.agent.feature.conversation.model.AgentConversation
import com.wavex.agent.feature.conversation.viewmodel.ConversationViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
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
 * ConversationViewModel 回归钉子（计划 Task 6 Review Focus）：
 * - 建选删改与空草稿复用；
 * - cancelDuringStreamRetainsOnlyVisiblePartialReply：流中取消只保留可见的部分回复并清除占位登记；
 * - lateTitleResultCannotReplaceManualName：手动改名后到达的标题生成结果被丢弃；
 * - 生成结束后 fake 仓库收到的最终 sync 包含完整回复（落盘最终内容）。
 *
 * 用真实时间模型（单线程 Main 执行器 + 真实 IO）：VM 的存储轮询永不结束，
 * runTest 的静默检查会挂死；这里所有等待都用 awaitUntil 轮询真实状态。
 */
class ConversationViewModelTest {

    private val mainExecutor = Executors.newSingleThreadExecutor()
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    // ---- fakes ----

    private class FakeConversationRepository : ConversationRepository {
        data class SyncCall(val snapshots: List<AgentConversationData>, val suppressed: Map<String, String>)
        val syncCalls = mutableListOf<SyncCall>()
        var loaded: List<ConversationSnapshot> = emptyList()
        override suspend fun loadAll(): List<ConversationSnapshot> = loaded
        override suspend fun sync(snapshots: List<AgentConversationData>, suppressedMessageIds: Map<String, String>) {
            syncCalls += SyncCall(snapshots.toList(), suppressedMessageIds.toMap())
        }
        var importFailAfterCalls: Int? = null   // 第 N 次调用起返回 0（写失败，部分导入）
        private var importCalls = 0
        val importedIds = mutableListOf<String>()
        override suspend fun importSessions(snapshots: List<AgentConversationData>): Int {
            importCalls++
            val failAfter = importFailAfterCalls
            if (failAfter != null && importCalls > failAfter) return 0
            importedIds += snapshots.map { it.id }
            return snapshots.size
        }
        override suspend fun refreshStorage(): SessionStorageStatus = SessionStorageStatus(false, "", false)
    }

    private open class FakeProviderSettings : ProviderSettingsRepository {
        val providers = mutableListOf(
            Provider(id = "p1", name = "测试站", baseUrl = "https://example.com", apiKey = "sk-test", model = "test-model")
        )
        var currentProviderId: String? = "p1"
        override fun loadProviders(): List<Provider> = providers.toList()
        override fun saveProviders(providers: List<Provider>) { this.providers.clear(); this.providers.addAll(providers) }
        override fun loadCurrentProviderId(): String? = currentProviderId
        override fun loadCurrentProvider(): Provider? = providers.firstOrNull { it.id == currentProviderId }
        override fun saveCurrentProviderId(id: String?) { currentProviderId = id }
        override fun loadFetchedModels(): Map<String, List<String>> = emptyMap()
        override fun saveFetchedModels(models: Map<String, List<String>>) {}
        override fun loadThemeChoice(): String = ""
        override fun saveThemeChoice(value: String) {}
        override fun loadReasoningEffort(): String = ""
        override fun saveReasoningEffort(value: String) {}
    }

    private class FakeModelService : ModelService {
        var deltas: List<String> = emptyList()
        var streamGate: CompletableDeferred<Unit>? = null
        var titleResult: String? = null
        var titleDelayMs: Long = 0
        var titleReturned = false
        val titleRequests = mutableListOf<String>()
        var streamCount = 0

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
            streamCount++
            deltas.forEach { onDelta(it, "") }
            streamGate?.await()
        }

        override suspend fun generateTitle(provider: Provider, transcript: String): String? {
            titleRequests += transcript
            kotlinx.coroutines.delay(titleDelayMs)
            titleReturned = true
            return titleResult
        }

        override suspend fun fetchModels(provider: Provider): List<String>? = emptyList()
        override suspend fun probe(provider: Provider): ConnectionReport =
            ConnectionReport(true, ApiProtocol.OPENAI, "", com.wavex.agent.core.domain.model.AuthDialect.BEARER, 1, 1L, null)
    }

    private class FakeProviderSettingsNoProvider : FakeProviderSettings() {
        override fun loadCurrentProvider(): Provider? = null
    }

    private val repo = FakeConversationRepository()
    private val settings = FakeProviderSettings()
    private val model = FakeModelService()

    private fun contentLoader(): ContentLoader = ContentLoader { null }
    private val fileWriter = GeneratedFileWriter { _, _, _ -> AttachmentWriteResult.TooLarge }

    private lateinit var vm: ConversationViewModel

    private fun makeVm(settingsRepo: ProviderSettingsRepository = settings): ConversationViewModel =
        ConversationViewModel(repo, settingsRepo, model, contentLoader(), fileWriter)

    @Before
    fun setUp() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        repo.syncCalls.clear()
        model.streamCount = 0
        model.titleRequests.clear()
        model.titleReturned = false
        model.titleDelayMs = 0
        model.streamGate = null
        Dispatchers.setMain(mainDispatcher)
        vm = makeVm()
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        mainDispatcher.close()
        Dispatchers.resetMain()
    }

    /** 真实时间轮询等待：条件满足或超时（生成引擎/轮询协程跑在真实 Main 执行器上） */
    private fun awaitUntil(desc: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("awaitUntil 超时: $desc")
            Thread.sleep(10)
        }
    }

    private fun sendUserMessage(conv: AgentConversation, text: String) {
        conv.appendMessage(ChatMessage(text = text, fromUser = true))
    }

    // ---- 建选删改 ----

    @Test
    fun `newConversation reuses an existing empty scratch draft`() {
        val first = vm.conversations.first()
        vm.newConversation()
        assertEquals(1, vm.conversations.size)          // 空草稿被复用，未新增
        assertEquals(first.id, vm.currentConversationId)
        vm.conversations.first().appendMessage(ChatMessage(text = "hi", fromUser = true))
        vm.newConversation()
        assertEquals(2, vm.conversations.size)          // 非空后新建第二个
        assertEquals(vm.conversations[0].id, vm.currentConversationId)
    }

    @Test
    fun `deleteConversation removes and falls back to a fresh conversation`() {
        vm.deleteConversation(vm.currentConversation)
        assertEquals(1, vm.conversations.size)
        assertTrue(vm.conversations.first().messages.isEmpty())
        assertEquals(vm.conversations.first().id, vm.currentConversationId)
    }

    @Test
    fun `renameConversation marks the title user defined`() {
        val conv = vm.currentConversation
        vm.renameConversation(conv, "  我的工作对话  ")
        assertEquals("我的工作对话", conv.title)
        assertTrue(conv.titleIsUserDefined)
    }

    // ---- 服务商联动（顶栏/抽屉的模型名展示） ----

    @Test
    fun `refreshSelectedProvider picks up model edited on catalog page`() {
        assertEquals("test-model", vm.selectedModel)
        // 模拟目录页 updateModel：CatalogViewModel 只写共享存储，不经过本 VM
        settings.providers[0] = settings.providers[0].copy(model = "kimi-k2-0711-preview")
        vm.refreshSelectedProvider()
        assertEquals("kimi-k2-0711-preview", vm.selectedModel)
    }

    @Test
    fun `refreshSelectedProvider follows provider switch and removal`() {
        settings.providers.add(Provider(id = "p2", name = "备用站", baseUrl = "https://b.example.com", apiKey = "sk-b", model = "backup-model"))
        settings.currentProviderId = "p2"
        vm.refreshSelectedProvider()
        assertEquals("backup-model", vm.selectedModel)
        // 删除当前服务商后回退占位文案（与 getter 语义一致）
        settings.currentProviderId = null
        vm.refreshSelectedProvider()
        assertEquals("未配置", vm.selectedModel)
    }

    // ---- 生成与落盘 ----

    @Test
    fun `no provider configured means send is a no-op`() {
        val noProviderVm = makeVm(FakeProviderSettingsNoProvider())
        val conv = noProviderVm.currentConversation
        sendUserMessage(conv, "你好")
        noProviderVm.startGeneration(conv)
        Thread.sleep(100)
        assertEquals(0, model.streamCount)
        assertEquals(1, conv.messages.size)             // 未追加占位
        assertFalse(noProviderVm.isGeneratingIn(conv.id))
        noProviderVm.viewModelScope.cancel()
    }

    @Test
    fun `generationEndSyncsFinalContent`() {
        model.deltas = listOf("你", "好")
        val conv = vm.currentConversation
        sendUserMessage(conv, "打招呼")
        vm.startGeneration(conv)
        awaitUntil("生成结束") { !vm.isGeneratingIn(conv.id) }
        assertEquals("你好", conv.messages.last().text)
        // 落盘最终内容：轮询拍（500ms）后的 sync 快照包含完整回复，且不再抑制占位
        awaitUntil("sync 收到最终内容") {
            val last = repo.syncCalls.lastOrNull { it.snapshots.any { d -> d.id == conv.id } }
            last != null && last.suppressed[conv.id] == null &&
                last.snapshots.first { d -> d.id == conv.id }
                    .nodes.values.last { it.id == conv.messages.last().id }.text == "你好"
        }
    }

    /**
     * 回答归属不能跟着顶栏漂：生成期间用户去模型页换了模型，
     * 已发出的这一条必须仍记着发请求时的模型名（落盘也带着）。
     */
    @Test
    fun `reply keeps the model that actually produced it`() {
        model.deltas = listOf("回答")
        model.streamGate = CompletableDeferred()
        val conv = vm.currentConversation
        sendUserMessage(conv, "问题")
        vm.startGeneration(conv)
        awaitUntil("占位已出现") { conv.messages.size == 2 }
        assertEquals("test-model", conv.messages.last().model)
        // 流式未结束时切到别的模型（等价于模型页 updateModel + 壳层 refresh）
        settings.providers[0] = settings.providers[0].copy(model = "other-model")
        vm.refreshSelectedProvider()
        assertEquals("other-model", vm.selectedModel)          // 顶栏已变
        model.streamGate?.complete(Unit)
        awaitUntil("生成结束") { !vm.isGeneratingIn(conv.id) }
        assertEquals("回答", conv.messages.last().text)
        assertEquals("test-model", conv.messages.last().model)  // 气泡归属不变
        // 落盘同样带模型名：重启后气泡仍能说清是谁写的
        awaitUntil("sync 带上模型名") {
            repo.syncCalls.lastOrNull { it.snapshots.any { d -> d.id == conv.id } }
                ?.snapshots?.first { d -> d.id == conv.id }
                ?.nodes?.get(conv.messages.last().id)?.model == "test-model"
        }
    }

    @Test
    fun `cancelDuringStreamRetainsOnlyVisiblePartialReply`() {
        model.deltas = listOf("部分")
        model.streamGate = CompletableDeferred()
        val conv = vm.currentConversation
        sendUserMessage(conv, "问题")
        vm.startGeneration(conv)
        awaitUntil("流式占位显示部分回复") { conv.messages.lastOrNull()?.text == "部分" }
        assertTrue(vm.isGeneratingIn(conv.id))
        vm.stopGeneration(conv.id)
        awaitUntil("生成状态清除") { !vm.isGeneratingIn(conv.id) }
        // 可见的部分回复保留（不被移除、不回退乱码）
        assertEquals("部分", conv.messages.last().text)
        // 占位登记清除：取消后的下一次 sync 不再抑制该会话
        awaitUntil("取消后 sync 不再抑制占位") {
            val last = repo.syncCalls.lastOrNull { it.snapshots.any { d -> d.id == conv.id } }
            last != null && last.suppressed[conv.id] == null
        }
    }

    @Test
    fun `lateTitleResultCannotReplaceManualName`() {
        model.deltas = listOf("回答内容")
        model.titleResult = "自动生成的标题"
        model.titleDelayMs = 1_000
        val conv = vm.currentConversation
        sendUserMessage(conv, "问题")
        vm.startGeneration(conv)
        awaitUntil("标题请求已发出") { model.titleRequests.size == 1 }
        // 标题请求仍在途（1s 延迟未到）时用户手动改名
        vm.renameConversation(conv, "手动命名")
        awaitUntil("迟到的标题请求返回") { model.titleReturned }
        assertEquals("手动命名", conv.title)          // 迟到的生成标题让位于手动命名
        assertTrue(conv.titleIsUserDefined)
    }

    @Test
    fun `importSnapshotsAssignsNewIdsAddsToListAndStopsOnPartialFailure`() {
        val conv1 = com.wavex.agent.core.domain.model.ConversationSnapshot(
            id = "old-1", title = "旧会话一", titleUserDefined = false, titleUserCount = 0,
            tree = com.wavex.agent.core.domain.model.TreeData(
                nodes = mapOf("n1" to com.wavex.agent.core.domain.model.StoredMessage(id = "n1", text = "你好", fromUser = true)),
                children = mapOf(com.wavex.agent.core.domain.model.TREE_ROOT to mutableListOf("n1")),
                activeChild = mapOf(com.wavex.agent.core.domain.model.TREE_ROOT to "n1"),
            ),
        )
        val conv2 = conv1.copy(id = "old-2", title = "旧会话二")
        // 第二个会话写入失败（部分导入）：repo 在登记 1 个后中止
        repo.importFailAfterCalls = 1
        // importSnapshots 是挂起函数；测试类跑在真实 Main 执行器，用协程 + awaitUntil 收割
        var result: Int? = null
        kotlinx.coroutines.MainScope().launch {
            result = vm.importSnapshots(listOf(conv1, conv2))
        }
        awaitUntil("导入完成") { result != null }
        val n = result!!
        assertEquals(1, n)
        // 成功的会话以全新 id 进入列表（永不与现有撞 id）
        assertEquals("旧会话一", vm.conversations.last().title)
        assertTrue(vm.conversations.last().id != "old-1")
        // 失败的会话不进列表
        assertEquals(1, vm.conversations.count { it.title == "旧会话一" })
        assertTrue(vm.conversations.none { it.title == "旧会话二" })
    }

    // ---- 排序不变量：发送才置顶（点击选中/复用空草稿不算使用） ----

    @Test
    fun `selectConversation keeps list order and timestamp`() {
        val older = AgentConversation("older", "较早", initialLastActiveAt = System.currentTimeMillis() - 10_000)
        vm.conversations.add(older)
        assertEquals("welcome", vm.conversations.first().id)
        vm.selectConversation(older)
        // 点击不算使用：只切换当前会话，列表不动、时间戳不刷
        assertEquals("welcome", vm.conversations.first().id)
        assertEquals("older", vm.currentConversationId)
    }

    @Test
    fun `newConversation reusing empty draft does not bump it`() {
        val draft = vm.conversations.first()          // 初始 welcome 即空草稿
        val stamp = System.currentTimeMillis() - 60_000
        draft.lastActiveAt = stamp
        val fresher = AgentConversation("fresher", "更新", initialLastActiveAt = System.currentTimeMillis() - 10_000)
        // 模拟真实列表序（活跃降序）：更新的会话在草稿之前
        vm.conversations.add(0, fresher)
        vm.newConversation()                          // 复用 welcome 草稿
        assertEquals(draft.id, vm.currentConversationId)
        // 复用不置顶不刷时间：草稿留在原位，活跃时间保持不变
        assertEquals(listOf("fresher", draft.id), vm.conversations.map { it.id })
        assertEquals(stamp, draft.lastActiveAt)
    }

    @Test
    fun `sendBumpsConversationToTopEvenWithoutProvider`() {
        val noProviderVm = makeVm(FakeProviderSettingsNoProvider())
        val older = AgentConversation("older", "较早", initialLastActiveAt = System.currentTimeMillis() - 10_000)
        noProviderVm.conversations.add(older)
        noProviderVm.selectConversation(older)
        // 无 provider 时发送本身是 no-op，但活跃刷新仍发生：发送者顶回最上
        val sender = noProviderVm.conversations.first { it.id != "older" }
        sendUserMessage(sender, "你好")
        noProviderVm.startGeneration(sender)
        assertEquals(sender.id, noProviderVm.conversations.first().id)
        noProviderVm.viewModelScope.cancel()
    }

    @Test
    fun `setPinnedConversationMovesConversationToPinnedHeadAndKeepsItThere`() {
        val a = AgentConversation("a", "甲", initialLastActiveAt = 300)
        val b = AgentConversation("b", "乙", initialLastActiveAt = 200)
        vm.conversations.clear()
        vm.conversations.add(a)
        vm.conversations.add(b)
        vm.setPinnedConversation(b, true)
        assertEquals(listOf("b", "a"), vm.conversations.map { it.id })   // 置顶区头
        assertTrue(b.pinned)
        // 未置顶会话活跃跳动不得越过置顶区
        vm.selectConversation(a)
        assertEquals(listOf("b", "a"), vm.conversations.map { it.id })
        vm.setPinnedConversation(b, false)
        assertEquals(listOf("b", "a"), vm.conversations.map { it.id })   // 取消置顶回到未置顶区头
        assertFalse(b.pinned)
    }

    @Test
    fun `setArchivedConversationMovesToBottomAndForcesUnpin`() {
        val a = AgentConversation("a", "甲", initialLastActiveAt = 300)
        val b = AgentConversation("b", "乙", initialLastActiveAt = 200)
        vm.conversations.clear()
        vm.conversations.add(a)
        vm.conversations.add(b)
        vm.setPinnedConversation(b, true)
        vm.setArchivedConversation(b, true)
        assertEquals(listOf("a", "b"), vm.conversations.map { it.id })   // 归档垫底
        assertTrue(b.archived)
        assertFalse(b.pinned)                                            // 归档即取消置顶
        vm.setArchivedConversation(b, false)
        assertEquals(listOf("b", "a"), vm.conversations.map { it.id })   // 取消归档回到未置顶区头
        assertFalse(b.archived)
    }

    // ---- 归档当前会话：当前会话必须仍是可见会话（回归：归档后问答写进列表看不见的会话） ----

    @Test
    fun `archivingCurrentConversationSwitchesToAnotherVisibleConversation`() {
        val current = vm.currentConversation
        sendUserMessage(current, "问题")
        val other = AgentConversation("other", "另一个", initialLastActiveAt = System.currentTimeMillis() - 10_000)
        vm.conversations.add(other)
        vm.setArchivedConversation(current, true)
        assertTrue(current.archived)
        assertEquals("other", vm.currentConversationId)
    }

    @Test
    fun `archivingLastConversationLeavesAFreshVisibleDraft`() {
        val only = vm.currentConversation
        vm.setArchivedConversation(only, true)
        // 归档最后一个会话后：当前会话是新的未归档空草稿（抽屉不会只剩「暂无对话」而无处输入）
        val current = vm.currentConversation
        assertFalse(current.archived)
        assertTrue(current.id != only.id)
        assertEquals("新对话", current.title)
        assertTrue(current.messages.isEmpty())
    }

    @Test
    fun `questionAskedAfterArchivingLastConversationStaysInVisibleConversation`() {
        val only = vm.currentConversation
        vm.setArchivedConversation(only, true)
        // 归档后的问答落在新的可见会话
        val target = vm.currentConversation
        sendUserMessage(target, "归档后的问题")
        vm.newConversation()
        // 新建会话后信息仍留在可见（未归档）会话里，不在被隐藏的归档会话
        assertTrue(vm.conversations.any { !it.archived && it.messages.any { m -> m.text == "归档后的问题" } })
    }

    @Test
    fun `batchArchivingCurrentConversationSwitchesAwayFromArchivedOne`() {
        val current = vm.currentConversation
        sendUserMessage(current, "问题")
        vm.archiveConversations(listOf(current.id))
        assertTrue(current.archived)
        assertFalse(vm.currentConversation.archived)
    }

    @Test
    fun `loadingOnlyArchivedConversationsStartsAFreshVisibleDraft`() {
        val archived = ConversationSnapshot(
            id = "archived-1", title = "旧会话", titleUserDefined = false, titleUserCount = 0,
            lastActiveAt = 1_000L, archived = true,
            tree = com.wavex.agent.core.domain.model.TreeData(
                nodes = mapOf("n1" to com.wavex.agent.core.domain.model.StoredMessage(id = "n1", text = "旧内容", fromUser = true)),
                children = mapOf(com.wavex.agent.core.domain.model.TREE_ROOT to mutableListOf("n1")),
                activeChild = mapOf(com.wavex.agent.core.domain.model.TREE_ROOT to "n1"),
            ),
        )
        repo.loaded = listOf(archived)
        val loadedVm = makeVm()
        // 加载协程在单线程 Main 上跑：同执行器的栅栏任务排在其后，保证 applySnapshots 已跑完
        val loadedLatch = java.util.concurrent.CountDownLatch(1)
        mainExecutor.execute { loadedLatch.countDown() }
        assertTrue("加载协程未在超时内完成", loadedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // 归档会话照常进列表，但当前会话必须是新建的可见草稿，不能是列表里看不见的归档会话
        assertTrue(loadedVm.conversations.any { it.id == "archived-1" })
        assertFalse(loadedVm.currentConversation.archived)
        loadedVm.viewModelScope.cancel()
    }

    @Test
    fun `archiveConversationsBatchArchivesSelectedUnpinsAndKeepsOthersInPlace`() {
        val a = AgentConversation("a", "甲", initialLastActiveAt = 300)
        val b = AgentConversation("b", "乙", initialLastActiveAt = 200)
        val c = AgentConversation("c", "丙", initialLastActiveAt = 100)
        vm.conversations.clear()
        vm.conversations.add(a)
        vm.conversations.add(b)
        vm.conversations.add(c)
        vm.setPinnedConversation(b, true)
        // 批量归档 b、c(含不存在的 id,容错);a 未选中不动
        vm.archiveConversations(listOf("b", "c", "ghost"))
        assertEquals(listOf("a", "b", "c"), vm.conversations.map { it.id })   // 未选中在前,归档垫底且保持原相对顺序
        assertTrue(b.archived)
        assertFalse(b.pinned)                                                 // 归档即取消置顶
        assertTrue(c.archived)
        assertFalse(a.archived)
    }
}
