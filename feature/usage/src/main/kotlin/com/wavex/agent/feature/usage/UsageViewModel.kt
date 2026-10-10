package com.wavex.agent.feature.usage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavex.agent.core.domain.model.UsagePageSnapshot
import com.wavex.agent.core.domain.model.UsageRange
import com.wavex.agent.core.domain.model.UsageSummary
import com.wavex.agent.core.domain.model.ProviderStat
import com.wavex.agent.core.domain.model.ModelStat
import com.wavex.agent.core.domain.model.TrendPoint
import com.wavex.agent.core.domain.model.UsageLogEntry
import com.wavex.agent.core.domain.model.resolveToMillis
import com.wavex.agent.core.domain.port.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 统计页一屏数据（同构 UsagePageSnapshot；独立类型避免 UI 依赖快照演进而加字段即破） */
data class UsagePageData(
    val summary: UsageSummary,
    val providerStats: List<ProviderStat>,
    val modelStats: List<ModelStat>,
    val trends: List<TrendPoint>,
    val recentLogs: List<UsageLogEntry>
) {
    companion object {
        val EMPTY = UsagePageData(
            UsageSummary(0, 0, 0, 0),
            emptyList(), emptyList(), emptyList(), emptyList()
        )
    }
}

/**
 * 用量统计域状态（Task 9）：范围/服务商筛选 + 一屏数据刷新。
 * 快速切换时取消上一个请求，只应用最新一次结果（与原 WavexViewModel 语义一致）。
 */
class UsageViewModel(
    private val usageRepository: UsageRepository
) : ViewModel() {

    /** 聚合查询的 IO 派发（可注入以便 JVM 单测用虚拟调度器替换，避免真实线程池等待） */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    /** 仅用量页可见时才响应落账回调（旧语义：不在用量 tab 不白解析 90 天日志） */
    var onPageVisible: () -> Boolean = { true }

    var usageRange by mutableStateOf<UsageRange>(UsageRange.Today)
        private set
    var usageProviderFilter by mutableStateOf<String?>(null)   // null = 全部
        private set
    var usageData by mutableStateOf(UsagePageData.EMPTY)
        private set

    private var refreshJob: Job? = null

    init {
        // 埋点落账回调：有新记录时刷新（回调在 IO 线程，mutableStateOf 赋值线程安全）；
        // onCleared 注销：repository 是进程单例，不注销会强引用已销毁的 VM
        usageRepository.setOnRecorded { if (onPageVisible()) refreshUsageData() }
    }

    override fun onCleared() {
        usageRepository.setOnRecorded(null)
        super.onCleared()
    }

    /** 重算统计页全部数据（进页面 / 范围或筛选变化 / 有新记录落账时调用） */
    fun refreshUsageData() {
        refreshJob?.cancel()
        val range = usageRange
        val filter = usageProviderFilter
        refreshJob = viewModelScope.launch {
            val zone = java.time.ZoneId.systemDefault()
            val (s, e) = range.resolveToMillis(java.time.LocalDate.now(zone), zone)
            val data = withContext(ioDispatcher) {
                // 单次组合查询：pageData 只读盘/解析一次
                usageRepository.pageData(s, e, filter).toPageData()
            }
            // 取消后的旧结果不落地（isActive 检查）
            if (isActive) usageData = data
        }
    }

    fun changeUsageRange(r: UsageRange) {
        usageRange = r
        refreshUsageData()
    }

    fun changeUsageProviderFilter(v: String?) {
        usageProviderFilter = v
        refreshUsageData()
    }

    private fun UsagePageSnapshot.toPageData() =
        UsagePageData(summary, providerStats, modelStats, trends, recentLogs)
}
