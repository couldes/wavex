package com.wavex.agent.core.data

import android.content.Context
import com.wavex.agent.core.data.usage.UsageAggregator
import com.wavex.agent.core.data.usage.UsageLogStore
import com.wavex.agent.core.data.usage.UsageRollup
import com.wavex.agent.core.data.usage.UsageRollupsFile
import com.wavex.agent.core.domain.model.ModelStat
import com.wavex.agent.core.domain.model.ProviderStat
import com.wavex.agent.core.domain.model.TrendPoint
import com.wavex.agent.core.domain.model.UsageLogEntry
import com.wavex.agent.core.domain.model.UsagePageSnapshot
import com.wavex.agent.core.domain.model.UsageSummary
import com.wavex.agent.core.domain.port.UsageRepository
import com.wavex.agent.core.domain.port.UsageSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 用量统计门面（UsageRepository + UsageSink 的数据层实现）：
 * - 明细持久化 → usage.UsageLogStore（usage_logs.json）
 * - rollup 持久化 → usage.UsageRollupsFile（usage_rollups.json，水位线防双计）
 * - 聚合口径 → usage.UsageAggregator（纯函数，rollup + 明细统一聚合）
 * 门面只做装配与记账循环（record/flush/防抖），查询语义与旧单文件实现逐行等价。
 */
class UsageStore internal constructor(
    private val logsFile: File, private val rollupsFile: File
) : UsageRepository, UsageSink {
    constructor(context: Context) : this(File(context.filesDir, "usage_logs.json"), File(context.filesDir, "usage_rollups.json"))

    private val logStore = UsageLogStore(logsFile)
    private var pendingEntries = mutableListOf<UsageLogEntry>()
    private lateinit var rollups: UsageRollupsFile

    internal var debounceMs: Long = 2000L
    private var onRecorded: (() -> Unit)? = null

    // 防抖落盘用的单一 IO 协程域：旧实现每次 record 都 new 一个
    // CoroutineScope(SupervisorJob() + IO)，纯垃圾分配（落账频率高：每条请求一次）
    private val ioScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private var job: Job? = null

    init {
        loadNow()
    }

    override fun setOnRecorded(listener: (() -> Unit)?) {
        onRecorded = listener
    }

    @Synchronized
    fun loadLogs(): List<UsageLogEntry> = logStore.loadLogs()

    @Synchronized
    fun saveNow() {
        runCatching {
            val stored = loadLogs()   // lastLoadFailed 时这里会重试读取，成功则清 flag
            // 坏文件且毫无新数据：拒绝用空数组覆盖 corrupt 原文；有新记录时照写（数据不丢）
            if (logStore.lastLoadFailed && stored.isEmpty() && pendingEntries.isEmpty()) return@runCatching
            val entries = stored + pendingEntries
            // 剪除由 mergeAndPrune 统一处理：返回值才是该写回磁盘的行
            //（只清 pending 不动 stored 的话，旧行每次全量重写都会写回，永不剪除）
            val keep = mergeAndPrune(entries)
            logStore.writeLogs(keep)
        }
    }

    override fun record(entry: UsageLogEntry) {
        runCatching {
            synchronized(this) {
                pendingEntries.add(entry)
                if (debounceMs <= 0L) {
                    // 同步模式（测试路径）：只入 pending，落盘由 flush/saveNow 统一触发——
                    // 同批多条记录一次合并，避免逐条落盘时旧行落入上一批推进后的水位线之下
                } else {
                    job?.cancel()
                    job = ioScope.launch {
                        delay(debounceMs); saveNow(); onRecorded?.invoke()
                    }
                }
            }
        }
    }

    /** 重载 rollups（测试钩子：手工注入聚合行后让查询可见；生产不调用） */
    internal fun reloadRollups() {
        rollups = UsageRollupsFile(rollupsFile).also { it.load() }
    }

    // ---------- 聚合查询（口径在 usage.UsageAggregator 内钉死） ----------

    @Synchronized
    fun summary(startMs: Long, endMs: Long, providerId: String? = null, kind: String? = null): UsageSummary {
        val rows = aggregate(startMs, endMs, providerId, kind)
        return UsageSummary(
            requests = rows.sumOf { it.requests },
            success = rows.sumOf { it.success },
            inputTokens = rows.sumOf { it.input },
            outputTokens = rows.sumOf { it.output }
        )
    }

    /** 服务商分组统计：按请求次数降序（次数相同按名称稳定排序） */
    @Synchronized
    fun providerStats(startMs: Long, endMs: Long, kind: String? = null): List<ProviderStat> {
        return aggregate(startMs, endMs, null, kind)
            .groupBy { it.providerId }
            .map { (pid, rs) ->
                ProviderStat(pid, rs.first().providerName,
                    rs.sumOf { it.requests }, rs.sumOf { it.success },
                    rs.sumOf { it.input }, rs.sumOf { it.output })
            }
            .sortedWith(compareByDescending<ProviderStat> { it.requests }.thenBy { it.providerName })
    }

    /** 模型分组统计（跨服务商同名模型合并；cc-switch get_model_stats 同口径） */
    @Synchronized
    fun modelStats(startMs: Long, endMs: Long, providerId: String? = null, kind: String? = null): List<ModelStat> {
        return aggregate(startMs, endMs, providerId, kind)
            .groupBy { it.model }
            .map { (model, rs) ->
                ModelStat(model, rs.sumOf { it.requests }, rs.sumOf { it.input }, rs.sumOf { it.output })
            }
            .sortedWith(compareByDescending<ModelStat> { it.requests }.thenBy { it.model })
    }

    /** 每日趋势：区间内每天一行（无数据补零），日期升序 */
    @Synchronized
    fun dailyTrends(startMs: Long, endMs: Long, providerId: String? = null, kind: String? = null): List<TrendPoint> {
        return UsageAggregator.dailyFill(aggregate(startMs, endMs, providerId, kind), startMs, endMs, java.time.ZoneId.systemDefault())
    }

    private fun aggregate(startMs: Long, endMs: Long, providerId: String?, kind: String?): List<UsageAggregator.AggRow> {
        val zone = java.time.ZoneId.systemDefault()
        val allDetails = loadLogs()
        // 最早明细 = rollup 覆盖期的终点标志（全量口径，不受筛选影响）
        val earliestDetail = allDetails.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE
        return UsageAggregator.aggregateRows(startMs, endMs, providerId, kind,
            rollups.rows, allDetails, earliestDetail, zone)
    }

    /**
     * 统计页一屏组合查询：单次 loadLogs（90 天明细整读整解析）完成全部五项聚合。
     * 旧路径 summary/providerStats/modelStats/trends/recentLogs 各自 loadLogs，
     * 切一次筛选要把同一份 JSON 完整解析 5 遍；收敛后每屏只读一次盘。
     */
    @Synchronized
    override fun pageData(startMs: Long, endMs: Long, providerId: String?, limit: Int): UsagePageSnapshot {
        val zone = java.time.ZoneId.systemDefault()
        val allDetails = loadLogs()
        val earliestDetail = allDetails.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE
        val rows = UsageAggregator.aggregateRows(startMs, endMs, providerId, null,
            rollups.rows, allDetails, earliestDetail, zone)
        val summary = UsageSummary(
            requests = rows.sumOf { it.requests },
            success = rows.sumOf { it.success },
            inputTokens = rows.sumOf { it.input },
            outputTokens = rows.sumOf { it.output }
        )
        // 服务商统计始终全量（筛选 chips 要列出区间内全部服务商，“所见即可筛”，
        // 对齐旧 providerStats(s,e) 不带筛选的口径）；有筛选时补一次免盘的内存重聚合
        val providerRows = if (providerId == null) rows
        else UsageAggregator.aggregateRows(startMs, endMs, null, null,
            rollups.rows, allDetails, earliestDetail, zone)
        val providerStats = providerRows.groupBy { it.providerId }
            .map { (pid, rs) ->
                ProviderStat(pid, rs.first().providerName,
                    rs.sumOf { it.requests }, rs.sumOf { it.success },
                    rs.sumOf { it.input }, rs.sumOf { it.output })
            }
            .sortedWith(compareByDescending<ProviderStat> { it.requests }.thenBy { it.providerName })
        val modelStats = rows.groupBy { it.model }
            .map { (model, rs) ->
                ModelStat(model, rs.sumOf { it.requests }, rs.sumOf { it.input }, rs.sumOf { it.output })
            }
            .sortedWith(compareByDescending<ModelStat> { it.requests }.thenBy { it.model })
        // 趋势：单日按小时（明细桶），跨日按天（聚合行补零）——与 trends()/dailyTrends() 同口径
        val singleDay = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate() ==
            java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        val trends = if (singleDay) {
            val inRange = allDetails.filter { e ->
                e.kind !in UsageAggregator.UNCOUNTED_KINDS && e.createdAt in startMs..endMs &&
                    (providerId == null || e.providerId == providerId)
            }
            UsageAggregator.hourlyBuckets(inRange, startMs, endMs, zone)
        } else {
            UsageAggregator.dailyFill(rows, startMs, endMs, zone)
        }
        // 最近日志：全类型可见（含标题/探测），与 recentLogs() 同口径
        val recentLogs = allDetails
            .filter { it.createdAt in startMs..endMs }
            .filter { providerId == null || it.providerId == providerId }
            .sortedByDescending { it.createdAt }
            .take(limit)
        return UsagePageSnapshot(summary, providerStats, modelStats, trends, recentLogs)
    }

    /**
     * 趋势查询（cc-switch 口径）：单日区间按小时（24 点），跨日按天。
     * 小时桶只走明细（明细保留 90 天，近期单日必有明细；rollup 只有日粒度，
     * >90 天的单日自定义区间趋势为空 —— 概览/表格仍走 aggregate 不受影响）。
     */
    @Synchronized
    fun trends(startMs: Long, endMs: Long, providerId: String? = null, kind: String? = null): List<TrendPoint> {
        val zone = java.time.ZoneId.systemDefault()
        val singleDay = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate() ==
            java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        return if (singleDay) {
            val rows = loadLogs().filter { e ->
                e.kind !in UsageAggregator.UNCOUNTED_KINDS &&
                    e.createdAt in startMs..endMs &&
                    (providerId == null || e.providerId == providerId) &&
                    (kind == null || e.kind == kind)
            }
            UsageAggregator.hourlyBuckets(rows, startMs, endMs, zone)
        } else {
            dailyTrends(startMs, endMs, providerId, kind)
        }
    }

    /** 最近请求日志：时间倒序 + limit 截断（全类型可见，含标题/探测） */
    @Synchronized
    fun recentLogs(
        startMs: Long, endMs: Long, providerId: String? = null,
        kind: String? = null, limit: Int = 50
    ): List<UsageLogEntry> {
        return loadLogs()
            .filter { it.createdAt in startMs..endMs }
            .filter { providerId == null || it.providerId == providerId }
            .filter { kind == null || it.kind == kind }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    fun flush() {
        runCatching {
            synchronized(this) {
                job?.cancel(); job = null
                saveNow()
                onRecorded?.invoke()
            }
        }
    }

    private fun mergeAndPrune(currentEntries: List<UsageLogEntry>): List<UsageLogEntry> {
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val cutoff = now - (DETAIL_RETENTION_DAYS * 24 * 60 * 60 * 1000L)

        // 合并区间 = [watermark, cutoff)：水位线之前的行视为已落账，永不重复合并
        val toMerge = currentEntries.filter { e -> e.createdAt >= rollups.watermark && e.createdAt < cutoff }
        if (toMerge.isNotEmpty()) {
            val newRowMap = LinkedHashMap<String, UsageRollup>()
            toMerge.forEach { e ->
                val localDate = java.time.Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalDate()
                val dateStr = localDate.toString()
                val key = "$dateStr|${e.providerId}|${e.model}|${e.kind}"
                // 不存在 → rollupOf 已含本次 1 次计数；存在 → 才 +1 累加
                //（原写法 existing = map[key] ?: build(...) 后无条件 +1，首条被计 2 次）
                val existing = newRowMap[key]
                newRowMap[key] = if (existing == null) {
                    UsageAggregator.rollupOf(dateStr, e)
                } else {
                    existing.copy(
                        requestCount = existing.requestCount + 1,
                        successCount = existing.successCount + (if (e.statusCode in 200..299) 1L else 0L),
                        inputTokens = existing.inputTokens + e.inputTokens,
                        outputTokens = existing.outputTokens + e.outputTokens
                    )
                }
            }

            rollups.addAll(newRowMap.values.toList())
            rollups.watermark = cutoff
            // 先写 rollups（含新水位线）再写明细：中途崩溃时残留旧行落在水位线之前，
            // 重跑只剪除不重复合并（崩溃安全推演见 plan Task 2）
            rollups.save()
        }

        pendingEntries.clear()   // pending 已全部并入 entries，写盘后不再需要
        return currentEntries.filter { e -> e.createdAt >= cutoff }
    }

    private fun loadNow() {
        try { rollups = UsageRollupsFile(rollupsFile); rollups.load() } catch (_: Exception) {}
    }

    companion object {
        internal const val DETAIL_RETENTION_DAYS = 90L

        internal fun hourlyBuckets(
            entries: List<UsageLogEntry>, startMs: Long, endMs: Long, zone: java.time.ZoneId
        ): List<TrendPoint> = UsageAggregator.hourlyBuckets(entries, startMs, endMs, zone)
    }
}
