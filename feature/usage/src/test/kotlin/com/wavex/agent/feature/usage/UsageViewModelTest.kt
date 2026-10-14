package com.wavex.agent.feature.usage

import com.wavex.agent.core.domain.model.UsagePageSnapshot
import com.wavex.agent.core.domain.model.UsageRange
import com.wavex.agent.core.domain.model.UsageSummary
import com.wavex.agent.core.domain.port.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * UsageViewModel 行为钉子（Task 9）：初始空态、范围/筛选变更触发刷新、
 * 取消旧请求（只应用最新一次结果）、落账回调刷新。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeRepo : UsageRepository {
        var snapshot = UsagePageSnapshot(UsageSummary(0, 0, 0, 0), emptyList(), emptyList(), emptyList(), emptyList())
        val queries = mutableListOf<Pair<LongRange, String?>>()
        var recordedListener: (() -> Unit)? = null
        override fun pageData(startMs: Long, endMs: Long, providerId: String?, limit: Int): UsagePageSnapshot {
            queries.add(startMs..endMs to providerId)
            return snapshot
        }
        override fun setOnRecorded(listener: (() -> Unit)?) { recordedListener = listener }
    }

    private lateinit var repo: FakeRepo

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeRepo()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = UsageViewModel(repo).apply { ioDispatcher = dispatcher }

    @Test
    fun `initial state is empty with today range and no filter`() {
        val m = vm()
        assertEquals(UsageRange.Today, m.usageRange)
        assertNull(m.usageProviderFilter)
        assertEquals(UsagePageData.EMPTY, m.usageData)
    }

    @Test
    fun `change range triggers refresh with resolved window`() = runTest(dispatcher) {
        val m = vm()
        m.changeUsageRange(UsageRange.Last7Days)
        advanceUntilIdle()
        assertEquals(1, repo.queries.size)
        assertEquals(UsageRange.Last7Days, m.usageRange)
    }

    @Test
    fun `change provider filter triggers refresh`() = runTest(dispatcher) {
        val m = vm()
        m.changeUsageProviderFilter("p1")
        advanceUntilIdle()
        assertEquals(1, repo.queries.size)
        assertEquals("p1", repo.queries[0].second)
        assertEquals("p1", m.usageProviderFilter)
        // 再切回全部
        m.changeUsageProviderFilter(null)
        advanceUntilIdle()
        assertEquals(2, repo.queries.size)
        assertNull(repo.queries[1].second)
    }

    @Test
    fun `stale query result is not applied after newer change`() = runTest(dispatcher) {
        val m = vm()
        val stale = UsagePageSnapshot(UsageSummary(1, 1, 1, 1), emptyList(), emptyList(), emptyList(), emptyList())
        val fresh = UsagePageSnapshot(UsageSummary(9, 9, 9, 9), emptyList(), emptyList(), emptyList(), emptyList())
        repo.snapshot = stale
        val first = launch { m.refreshUsageData() }
        runCurrent()   // 第一个查询进入 IO 挂起点但未返回（同调度器下挂起即让出）
        // 在第一个查询完成前切换筛选：旧 job 取消
        repo.snapshot = fresh
        m.changeUsageProviderFilter("p2")
        advanceUntilIdle()
        first.cancelAndJoin()
        // 被取消的旧结果不落地；当前数据是最新查询的
        assertEquals(fresh.summary, m.usageData.summary)
        assertEquals(9L, m.usageData.summary.requests)
    }

    @Test
    fun `onRecorded callback triggers refresh`() = runTest(dispatcher) {
        val m = vm()
        repo.recordedListener = { m.refreshUsageData() }
        advanceUntilIdle()
        val before = repo.queries.size
        repo.snapshot = UsagePageSnapshot(UsageSummary(5, 4, 100, 50), emptyList(), emptyList(), emptyList(), emptyList())
        repo.recordedListener?.invoke()
        advanceUntilIdle()
        assertEquals(before + 1, repo.queries.size)
        assertEquals(5L, m.usageData.summary.requests)
    }
}
