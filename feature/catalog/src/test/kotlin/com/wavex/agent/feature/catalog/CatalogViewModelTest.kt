package com.wavex.agent.feature.catalog

import com.wavex.agent.core.domain.model.ApiProtocol
import com.wavex.agent.core.domain.model.AuthDialect
import com.wavex.agent.core.domain.model.ConnectionReport
import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.SessionStorageStatus
import com.wavex.agent.core.domain.port.ConversationRepository
import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.core.domain.port.ProviderSettingsRepository
import com.wavex.agent.core.domain.chat.ReasoningLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CatalogViewModel 行为钉子（Task 8）：服务商 CRUD/选中、模型拉取成功/失败保留旧列表、
 * 思考档位与主题持久化、存储状态刷新、旧备份导入成功/失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeSettings : ProviderSettingsRepository {
        val saved = mutableListOf<List<Provider>>()
        var currentId: String? = null
        var models = mutableMapOf<String, List<String>>()
        var theme = ""
        var effort = ""
        val modelSaves = mutableListOf<Map<String, List<String>>>()
        val themeSaves = mutableListOf<String>()
        val effortSaves = mutableListOf<String>()
        override fun loadProviders(): List<Provider> = saved.lastOrNull() ?: emptyList()
        override fun saveProviders(providers: List<Provider>) { saved.add(providers) }
        override fun loadCurrentProviderId(): String? = currentId
        override fun loadCurrentProvider(): Provider? =
            (saved.lastOrNull() ?: emptyList()).firstOrNull { it.id == currentId }
        override fun saveCurrentProviderId(id: String?) { currentId = id }
        override fun loadFetchedModels(): Map<String, List<String>> = models
        override fun saveFetchedModels(m: Map<String, List<String>>) { models = m.toMutableMap(); modelSaves.add(m) }
        override fun loadThemeChoice(): String = theme
        override fun saveThemeChoice(value: String) { theme = value; themeSaves.add(value) }
        override fun loadReasoningEffort(): String = effort
        override fun saveReasoningEffort(value: String) { effort = value; effortSaves.add(value) }
    }

    private class FakeRepo : ConversationRepository {
        var storageStatus = SessionStorageStatus(false, "", false)
        var refreshCalls = 0
        var failRefresh = false
        override suspend fun loadAll(): List<ConversationSnapshot> = emptyList()
        override suspend fun sync(snapshots: List<AgentConversationData>, suppressedMessageIds: Map<String, String>) {}
        override suspend fun importSessions(snapshots: List<AgentConversationData>): Int = 0
        override suspend fun refreshStorage(): SessionStorageStatus {
            refreshCalls++
            if (failRefresh) throw java.io.IOException("disk gone")
            return storageStatus
        }
    }

    private class FakeService : ModelService {
        var models: List<String>? = null
        var failFetch = false
        var fetchCalls = 0
        override suspend fun streamChat(provider: Provider, history: List<com.wavex.agent.core.domain.model.ChatRequestMessage>, reasoningEffort: String?, webSearch: Boolean, onDelta: (String, String) -> Unit, onImage: (String) -> Unit, onToolCalls: (List<com.wavex.agent.core.domain.model.ToolCall>) -> Unit) {}
        override suspend fun generateTitle(provider: Provider, transcript: String): String? = null
        override suspend fun fetchModels(provider: Provider): List<String>? {
            fetchCalls++
            if (failFetch) throw java.io.IOException("network down")
            return models
        }
        override suspend fun probe(provider: Provider): ConnectionReport =
            ConnectionReport(false, ApiProtocol.OPENAI, "", AuthDialect.BEARER, null, 0L, null)    }

    private lateinit var settings: FakeSettings
    private lateinit var repo: FakeRepo
    private lateinit var service: FakeService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings = FakeSettings()
        repo = FakeRepo()
        service = FakeService()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = CatalogViewModel(settings, repo, service)

    private fun provider(id: String, model: String = "gpt-test") =
        Provider(id = id, name = "P-$id", baseUrl = "https://x", apiKey = "key", model = model)

    // ---------- 服务商 CRUD / 选中 ----------

    @Test
    fun `upsert adds new provider and auto selects it when none selected`() {
        val m = vm()
        val p = provider("a")
        m.upsertProvider(p)
        assertEquals(listOf(p), m.providers)
        assertEquals("a", m.currentProviderId)
        assertEquals(listOf(listOf(p)), settings.saved)
    }

    @Test
    fun `editing non current provider does not switch selection`() {
        val m = vm()
        m.upsertProvider(provider("a"))
        m.upsertProvider(provider("b"))
        // b 新增时 a 已选中，保持 a
        assertEquals("a", m.currentProviderId)
        // 编辑 a（当前项）也不改变选中
        m.upsertProvider(provider("a", model = "gpt-2"))
        assertEquals("a", m.currentProviderId)
        val edited: Provider? = m.providers.firstOrNull { pv: Provider -> pv.id == "a" }
        assertEquals("gpt-2", edited?.model)
    }

    @Test
    fun `remove current provider falls back to first remaining`() {
        val m = vm()
        m.upsertProvider(provider("a"))
        m.upsertProvider(provider("b"))
        m.selectProvider(provider("b"))
        assertEquals("b", m.currentProviderId)
        m.removeProvider(provider("b"))
        assertEquals("a", m.currentProviderId)
        assertEquals(listOf(provider("a")), m.providers)
    }

    @Test
    fun `update model edits current provider only`() {
        val m = vm()
        m.upsertProvider(provider("a"))
        m.upsertProvider(provider("b"))
        m.updateModel("gpt-x")
        val a: Provider? = m.providers.firstOrNull { pv: Provider -> pv.id == "a" }
        val b: Provider? = m.providers.firstOrNull { pv: Provider -> pv.id == "b" }
        assertEquals("gpt-x", a?.model)
        assertEquals("gpt-test", b?.model)
    }

    // ---------- 模型拉取：成功登记 / 失败保留旧列表 ----------

    @Test
    fun `fetch models success records list and persists`() = runTest(dispatcher) {
        val m = vm()
        m.upsertProvider(provider("a"))
        service.models = listOf("m1", "m2")
        val job = launch { m.fetchModels() }
        advanceUntilIdle()
        job.cancelAndJoin()
        assertEquals(listOf("m1", "m2"), m.modelsFetched["a"])
        assertEquals(listOf("m1", "m2"), settings.models["a"])
        val loadingAfterSuccess: Boolean? = m.modelsLoading["a"]
        assertFalse(loadingAfterSuccess ?: true)
    }

    @Test
    fun `fetch models failure keeps previous list and clears loading`() = runTest(dispatcher) {
        val m = vm()
        m.upsertProvider(provider("a"))
        service.models = listOf("m1")
        val ok = launch { m.fetchModels() }
        advanceUntilIdle()
        ok.cancelAndJoin()
        // 第二次拉取失败：列表保留 m1
        service.failFetch = true
        val bad = launch { m.fetchModels() }
        advanceUntilIdle()
        bad.cancelAndJoin()
        assertEquals(listOf("m1"), m.modelsFetched["a"])
        val loadingAfterFailure: Boolean? = m.modelsLoading["a"]
        assertFalse(loadingAfterFailure ?: true)
    }

    // ---------- 偏好持久化 ----------

    @Test
    fun `reasoning effort persists and loads normalized`() {
        settings.effort = "minimal"
        val m = vm()
        // init 归一化：minimal → low（ReasoningLevels.normalize）
        assertEquals("low", m.reasoningEffort)
        m.changeReasoningEffort("high")
        assertEquals("high", m.reasoningEffort)
        assertEquals(listOf("high"), settings.effortSaves)
    }

    @Test
    fun `theme choice persists and loads with fallback`() {
        settings.theme = "DARK"
        val m = vm()
        assertEquals(CatalogViewModel.ThemeChoice.DARK, m.themeChoice)
        m.changeThemeChoice(CatalogViewModel.ThemeChoice.LIGHT)
        assertEquals(CatalogViewModel.ThemeChoice.LIGHT, m.themeChoice)
        assertEquals(listOf("LIGHT"), settings.themeSaves)
        // 损坏值回退跟随系统
        settings.theme = "GARBAGE"
        val m2 = vm()
        assertEquals(CatalogViewModel.ThemeChoice.SYSTEM, m2.themeChoice)
    }

    // ---------- 存储状态 ----------

    @Test
    fun `refresh storage surfaces status and swallows io failure`() = runTest(dispatcher) {
        val m = vm()
        repo.storageStatus = SessionStorageStatus(true, "/tmp/x", true)
        val job = launch { m.refreshSessionStorage() }
        advanceUntilIdle()
        job.cancelAndJoin()
        assertEquals(1, repo.refreshCalls)
        assertTrue(m.sessionStorageStatus.usingPublic)
        // IO 失败：状态保持旧值、不崩
        repo.failRefresh = true
        val bad = launch { m.refreshSessionStorage() }
        advanceUntilIdle()
        bad.cancelAndJoin()
        assertTrue(m.sessionStorageStatus.usingPublic)
    }

}
