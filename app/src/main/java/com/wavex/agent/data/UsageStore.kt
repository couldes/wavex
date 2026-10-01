package com.wavex.agent.data

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UsageLogEntry(
    val id: String, val kind: String,
    val providerId: String, val providerName: String, val model: String,
    val inputTokens: Long, val outputTokens: Long,
    val statusCode: Int, val latencyMs: Long,
    val createdAt: Long, val error: String
)

data class UsageSummary(
    val requests: Long, val success: Long,
    val inputTokens: Long, val outputTokens: Long
)

/** 服务商维度统计（供 ProviderStatsTable） */
data class ProviderStat(
    val providerId: String, val providerName: String,
    val requests: Long, val success: Long,
    val inputTokens: Long, val outputTokens: Long
)

/** 模型维度统计（跨服务商同名模型合并） */
data class ModelStat(
    val model: String,
    val requests: Long, val inputTokens: Long, val outputTokens: Long
)

/**
 * 趋势点（供柱状图；无数据补零）：
 * 日桶 key=ISO 日期（"2025-10-01"），小时桶 key=小时标签（"14时"），图表按 key 直接标注。
 */
data class TrendPoint(
    val key: String, val requests: Long,
    val inputTokens: Long, val outputTokens: Long
)

data class UsageRollup(
    val date: String, val providerId: String, val providerName: String,
    val model: String, val kind: String,
    val requestCount: Long, val successCount: Long,
    val inputTokens: Long, val outputTokens: Long
) {
    companion object {
        fun empty(): UsageRollup = UsageRollup("", "", "", "", "", 0, 0, 0, 0)
    }
}

class UsageRollupsFile @JvmOverloads constructor(
    private val file: File,
    initialWatermark: Long? = null
) {
    // 默认 0 而非 now：老用户升级后首次 save 才能把历史明细合并进 rollups；
    // 若从 now 起算，所有存量明细永远落在水位线之前、永不剪除（plan 明确：首欠视为 0）
    var watermark: Long = initialWatermark ?: 0L
    private var _rows = mutableListOf<UsageRollup>()
    val rows: List<UsageRollup> get() = _rows.sortedBy { it.date }

    fun load(): Boolean {
        return try {
            if (!file.exists()) { _rows.clear(); return false }
            val raw = file.readText()
            val o = JSONObject(raw)
            watermark = o.optLong("rolledUpBefore", 0L)
            _rows.clear()
            val arr = o.getJSONArray("rows")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                _rows.add(UsageRollup(
                    date = e.optString("date", ""),
                    providerId = e.optString("providerId", ""),
                    providerName = e.optString("providerName", ""),
                    model = e.optString("model", ""),
                    kind = e.optString("kind", ""),
                    requestCount = e.optLong("requestCount", 0),
                    successCount = e.optLong("successCount", 0),
                    inputTokens = e.optLong("inputTokens", 0),
                    outputTokens = e.optLong("outputTokens", 0)
                ))
            }
            true
        } catch (_: Exception) { _rows.clear(); false }
    }

    fun save() {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            val o = JSONObject().apply {
                put("rolledUpBefore", watermark)
                val arr = JSONArray()
                _rows.forEach { r ->
                    val row = JSONObject()
                    putIfNotNull(row, "date", r.date); putIfNotNull(row, "providerId", r.providerId)
                    putIfNotNull(row, "providerName", r.providerName); putIfNotNull(row, "model", r.model)
                    putIfNotNull(row, "kind", r.kind); putIfNotNull(row, "requestCount", r.requestCount)
                    putIfNotNull(row, "successCount", r.successCount); putIfNotNull(row, "inputTokens", r.inputTokens)
                    putIfNotNull(row, "outputTokens", r.outputTokens)
                    arr.put(row)
                }
                put("rows", arr)
            }
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                // 同上：Windows 下先删旧文件再重试（水位线丢失只会导致明细不剪除，不会双计）
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: Exception) { tmp.delete() }
    }

    fun addAll(newRows: List<UsageRollup>) { _rows.addAll(newRows) }

    private fun putIfNotNull(o: JSONObject, key: String, value: Any?) {
        if (value == null) return
        when (value) { is String -> o.put(key, value); is Int -> o.put(key, value.toLong()); else -> o.put(key, value) }
    }
}

class UsageStore internal constructor(
    private val logsFile: File, private val rollupsFile: File
) {
    constructor(context: Context) : this(File(context.filesDir, "usage_logs.json"), File(context.filesDir, "usage_rollups.json"))

    private var pendingEntries = mutableListOf<UsageLogEntry>()
    private lateinit var rollups: UsageRollupsFile

    // 「没读出来」不等于「用户删光了」：只有真正解析失败时才拦住空覆盖（对齐 ProviderStore 语义）
    private var lastLoadFailed = false

    internal var debounceMs: Long = 2000L
    var onRecorded: (() -> Unit)? = null
    // 防抖落盘用的单一 IO 协程域：旧实现每次 record 都 new 一个
    // CoroutineScope(SupervisorJob() + IO)，纯垃圾分配（落账频率高：每条请求一次）
    private val ioScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private var job: Job? = null

    init {
        loadNow()
    }

    @Synchronized
    fun loadLogs(): List<UsageLogEntry> {
        return try {
            if (!logsFile.exists()) return emptyList()
            val raw = logsFile.readText()
            val arr = JSONArray(raw)
            val result = mutableListOf<UsageLogEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(UsageLogEntry(
                    id = o.optString("id", ""), kind = o.optString("kind", "chat"),
                    providerId = o.optString("providerId", ""), providerName = o.optString("providerName", "未知"),
                    model = o.optString("model", "unknown"), inputTokens = o.optLong("inputTokens", 0),
                    outputTokens = o.optLong("outputTokens", 0), statusCode = o.optInt("statusCode", 0),
                    latencyMs = o.optLong("latencyMs", 0), createdAt = o.optLong("createdAt", 0),
                    error = o.optString("error", "")
                ))
            }
            val sorted = result.sortedBy { it.createdAt }
            lastLoadFailed = false   // 本次读取成功：之前坏过的表已被修复或重写
            return sorted
        } catch (e: Exception) {
            // 整表坏掉：原文留一份可手工恢复的副本，别让下一次保存把它冲没
            lastLoadFailed = true
            backupCorrupt(try { logsFile.readText() } catch (ignore: Exception) { "" })
            emptyList()
        }
    }

    /** 坏表原文写入 .corrupt 副本；写失败静默（主路径不因备份受阻） */
    private fun backupCorrupt(raw: String) {
        try {
            File(logsFile.parent, "${logsFile.name}.corrupt").writeText(raw)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun saveNow() {
        runCatching {
            val stored = loadLogs()   // lastLoadFailed 时这里会重试读取，成功则清 flag
            // 坏文件且毫无新数据：拒绝用空数组覆盖 corrupt 原文；有新记录时照写（数据不丢）
            if (lastLoadFailed && stored.isEmpty() && pendingEntries.isEmpty()) return@runCatching
            val entries = stored + pendingEntries
            // 剪除由 mergeAndPrune 统一处理：返回值才是该写回磁盘的行
            //（只清 pending 不动 stored 的话，旧行每次全量重写都会写回，永不剪除）
            val keep = mergeAndPrune(entries)

            val arr = JSONArray()
            keep.forEach { e ->
                val o = JSONObject()
                putIfNotNull(o, "id", e.id); putIfNotNull(o, "kind", e.kind)
                putIfNotNull(o, "providerId", e.providerId); putIfNotNull(o, "providerName", e.providerName)
                putIfNotNull(o, "model", e.model); putIfNotNull(o, "inputTokens", e.inputTokens)
                putIfNotNull(o, "outputTokens", e.outputTokens); putIfNotNull(o, "statusCode", e.statusCode)
                putIfNotNull(o, "latencyMs", e.latencyMs); putIfNotNull(o, "createdAt", e.createdAt)
                putIfNotNull(o, "error", e.error.take(300))
                arr.put(o)
            }
            
            val tmp = File(logsFile.parentFile, logsFile.name + ".tmp")
            try {
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(logsFile)) {
                    // Windows：rename 到已存在目标会失败 —— 删旧文件后重试一次
                    //（ConversationStore 同款；抄漏了 file.delete() 会导致第二次写入永远不生效）
                    logsFile.delete()
                    if (!tmp.renameTo(logsFile)) tmp.delete()
                }
            } catch (_: Exception) {}
        }
    }

    private fun putIfNotNull(o: JSONObject, key: String, value: Any?) {
        if (value == null) return
        when (value) { is String -> o.put(key, value); is Int -> o.put(key, value.toLong()); else -> o.put(key, value) }
    }

    fun record(entry: UsageLogEntry) {
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

    // ---------- 聚合查询（plan Task 3：口径钉死，对齐 cc-switch 的边界日语义） ----------

    /** 聚合中间行：rollup 行与明细行的统一形态 */
    private data class AggRow(
        val date: String, val providerId: String, val providerName: String,
        val model: String, val kind: String,
        val requests: Long, val success: Long, val input: Long, val output: Long
    )

    /**
     * 统一聚合器：rollup 行（整天完整落区间且早于最早明细）+ 明细行（逐行按本地日分桶）。
     * 口径：跨界日只由明细贡献 —— rollup 天的 dayEnd <= 最早明细时间才计入，
     * 同一天既被滚动过又有明细残留时绝不双计（cc-switch「排除部分边界日」同款取舍）。
     */
    @Synchronized
    private fun aggregate(
        startMs: Long, endMs: Long, providerId: String?, kind: String?
    ): List<AggRow> {
        val zone = java.time.ZoneId.systemDefault()
        val allDetails = loadLogs()
        // 最早明细 = rollup 覆盖期的终点标志（全量口径，不受筛选影响）
        val earliestDetail = allDetails.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE

        val rows = mutableListOf<AggRow>()

        // 1) rollup 行：整天 [dayStart, dayEnd] ⊆ [startMs, endMs] 且 dayEnd <= 最早明细
        rollups.rows.forEach { r ->
            if (providerId != null && r.providerId != providerId) return@forEach
            if (kind != null && r.kind != kind) return@forEach
            val day = try { java.time.LocalDate.parse(r.date) } catch (_: Exception) { return@forEach }
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            if (dayStart >= startMs && dayEnd <= endMs && dayEnd <= earliestDetail) {
                rows.add(AggRow(r.date, r.providerId, r.providerName, r.model, r.kind,
                    r.requestCount, r.successCount, r.inputTokens, r.outputTokens))
            }
        }

        // 2) 明细行：区间内、按筛选、逐行进按日分桶的聚合
        val detailAgg = LinkedHashMap<String, AggRow>()
        allDetails.forEach { e ->
            if (e.createdAt < startMs || e.createdAt > endMs) return@forEach
            if (providerId != null && e.providerId != providerId) return@forEach
            if (kind != null && e.kind != kind) return@forEach
            val date = java.time.Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalDate().toString()
            val key = "$date|${e.providerId}|${e.model}|${e.kind}"
            val cur = detailAgg[key]
            detailAgg[key] = if (cur == null) {
                AggRow(date, e.providerId, e.providerName, e.model, e.kind,
                    1, if (e.statusCode in 200..299) 1L else 0L, e.inputTokens, e.outputTokens)
            } else {
                cur.copy(
                    requests = cur.requests + 1,
                    success = cur.success + (if (e.statusCode in 200..299) 1L else 0L),
                    input = cur.input + e.inputTokens,
                    output = cur.output + e.outputTokens
                )
            }
        }
        rows.addAll(detailAgg.values)
        return rows
    }

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
        val zone = java.time.ZoneId.systemDefault()
        val byDate = aggregate(startMs, endMs, providerId, kind)
            .groupBy { it.date }
            .mapValues { (_, rs) ->
                Triple(rs.sumOf { it.requests }, rs.sumOf { it.input }, rs.sumOf { it.output })
            }

        var day = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val lastDay = java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        val out = mutableListOf<TrendPoint>()
        while (!day.isAfter(lastDay)) {
            val key = day.toString()
            val (req, input, output) = byDate[key] ?: Triple(0L, 0L, 0L)
            out.add(TrendPoint(key, req, input, output))
            day = day.plusDays(1)
        }
        return out
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
                e.createdAt in startMs..endMs &&
                    (providerId == null || e.providerId == providerId) &&
                    (kind == null || e.kind == kind)
            }
            hourlyBuckets(rows, startMs, endMs, zone)
        } else {
            dailyTrends(startMs, endMs, providerId, kind)
        }
    }

    /** 最近请求日志：时间倒序 + limit 截断 */
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
                // 不存在 → buildRollupKey 已含本次 1 次计数；存在 → 才 +1 累加
                //（原写法 existing = map[key] ?: build(...) 后无条件 +1，首条被计 2 次）
                val existing = newRowMap[key]
                newRowMap[key] = if (existing == null) {
                    buildRollupKey(dateStr, e)
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

    private fun buildRollupKey(dateStr: String, entry: UsageLogEntry): UsageRollup {
        return UsageRollup(
            date = dateStr, providerId = entry.providerId, providerName = entry.providerName,
            model = entry.model, kind = entry.kind,
            requestCount = 1, successCount = if (entry.statusCode in 200..299) 1L else 0L,
            inputTokens = entry.inputTokens, outputTokens = entry.outputTokens
        )
    }

    private fun loadNow() {
        try { rollups = UsageRollupsFile(rollupsFile); rollups.load() } catch (_: Exception) {}
    }

    companion object {
        internal const val DETAIL_RETENTION_DAYS = 90L

        /**
         * 纯函数：明细行按本地小时分桶（单日区间用，cc-switch 口径：今日按小时 24 点）。
         * 固定 24 个桶（0..23 时）升序，无数据补零；区间外的行直接丢弃。
         * key 为小时标签（"14时"），图表轴与点击明细直接展示。
         */
        internal fun hourlyBuckets(
            entries: List<UsageLogEntry>, startMs: Long, endMs: Long, zone: java.time.ZoneId
        ): List<TrendPoint> {
            val inRange = entries.filter { it.createdAt in startMs..endMs }
            val byHour = inRange.groupBy {
                java.time.Instant.ofEpochMilli(it.createdAt).atZone(zone).hour
            }
            return (0..23).map { h ->
                val es = byHour[h].orEmpty()
                TrendPoint(
                    key = "${h}时",
                    requests = es.size.toLong(),
                    inputTokens = es.sumOf { it.inputTokens },
                    outputTokens = es.sumOf { it.outputTokens }
                )
            }
        }
    }
}

// ---------- 日期范围（plan Task 8：纯函数，JVM 可测） ----------

/** 统计页日期范围：预设 + 自定义（UI 侧用 clampCustomRange 钳制后构造 Custom） */
sealed class UsageRange {
    data object Today : UsageRange()
    data object Yesterday : UsageRange()
    data object Last7Days : UsageRange()
    data object Last30Days : UsageRange()
    data object ThisMonth : UsageRange()
    data class Custom(val startMs: Long, val endMs: Long) : UsageRange()
}

/** 预设解析为闭区间 [startMs, endMs]（本地时区；end = 当天 23:59:59.999） */
fun UsageRange.resolveToMillis(today: java.time.LocalDate, zone: java.time.ZoneId): Pair<Long, Long> {
    fun startOf(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endOf(d: java.time.LocalDate) = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    return when (this) {
        is UsageRange.Today -> startOf(today) to endOf(today)
        is UsageRange.Yesterday -> startOf(today.minusDays(1)) to endOf(today.minusDays(1))
        is UsageRange.Last7Days -> startOf(today.minusDays(6)) to endOf(today)
        is UsageRange.Last30Days -> startOf(today.minusDays(29)) to endOf(today)
        is UsageRange.ThisMonth ->
            startOf(today.withDayOfMonth(1)) to endOf(today.withDayOfMonth(today.lengthOfMonth()))
        is UsageRange.Custom -> startMs to endMs
    }
}

/**
 * 自定义范围钳制：end < start 先交换；跨度超过 maxDays 保 end、
 * start 收到 end 往前 (maxDays-1) 天的 00:00（防呆上限，不是数据上限）
 */
fun clampCustomRange(startMs: Long, endMs: Long, maxDays: Int = 30, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Pair<Long, Long> {
    val (s, e) = if (endMs < startMs) endMs to startMs else startMs to endMs
    val startDay = java.time.Instant.ofEpochMilli(s).atZone(zone).toLocalDate()
    val endDay = java.time.Instant.ofEpochMilli(e).atZone(zone).toLocalDate()
    val spanDays = java.time.temporal.ChronoUnit.DAYS.between(startDay, endDay) + 1
    if (spanDays <= maxDays) return s to e
    val clampedStart = endDay.minusDays((maxDays - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    return clampedStart to e
}
