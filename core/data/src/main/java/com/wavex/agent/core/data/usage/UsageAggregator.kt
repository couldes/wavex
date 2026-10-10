package com.wavex.agent.core.data.usage

import com.wavex.agent.core.domain.model.TrendPoint
import com.wavex.agent.core.domain.model.UsageLogEntry

/**
 * 用量聚合纯逻辑（plan Task 3：口径钉死，对齐 cc-switch 的边界日语义）：
 * rollup 行（整天完整落区间且早于最早明细）+ 明细行（逐行按本地日分桶）的统一聚合、
 * 日/小时分桶、明细行 → rollup 行的构造。不碰文件：明细与 rollup 行由调用方
 * （UsageStore 门面）读出后传入，单次读盘可复用。
 */
internal object UsageAggregator {

    /**
     * 辅助请求类型：落明细日志（「最近请求」可见）但不计入用量统计口径。
     * 标题/探测都是低消耗的附带请求，计入只会稀释真实的对话用量。
     */
    val UNCOUNTED_KINDS = setOf("title", "probe")

    /** 聚合中间行：rollup 行与明细行的统一形态 */
    data class AggRow(
        val date: String, val providerId: String, val providerName: String,
        val model: String, val kind: String,
        val requests: Long, val success: Long, val input: Long, val output: Long
    )

    /**
     * 统一聚合器：rollup 行（整天完整落区间且早于最早明细）+ 明细行（逐行按本地日分桶）。
     * 口径：跨界日只由明细贡献 —— rollup 天的 dayEnd <= 最早明细时间才计入，
     * 同一天既被滚动过又有明细残留时绝不双计（cc-switch「排除部分边界日」同款取舍）。
     * 统计只计对话（chat）：标题/探测是辅助请求，落明细日志但不计入任何统计查询
     * （存量行也随查询排除，显式 kind=辅助类型 查询返回空）。
     */
    fun aggregateRows(
        startMs: Long, endMs: Long, providerId: String?, kind: String?,
        rollupRows: List<UsageRollup>,
        allDetails: List<UsageLogEntry>, earliestDetail: Long, zone: java.time.ZoneId
    ): List<AggRow> {
        val rows = mutableListOf<AggRow>()

        // 1) rollup 行：整天 [dayStart, dayEnd] ⊆ [startMs, endMs] 且 dayEnd <= 最早明细
        rollupRows.forEach { r ->
            if (r.kind in UNCOUNTED_KINDS) return@forEach
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
            if (e.kind in UNCOUNTED_KINDS) return@forEach
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

    /** 聚合行 → 零填充日序列（pageData 与 dailyTrends 共用） */
    fun dailyFill(
        rows: List<AggRow>, startMs: Long, endMs: Long, zone: java.time.ZoneId
    ): List<TrendPoint> {
        val byDate = rows.groupBy { it.date }
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
     * 纯函数：明细行按本地小时分桶（单日区间用，cc-switch 口径：今日按小时 24 点）。
     * 固定 24 个桶（0..23 时）升序，无数据补零；区间外的行直接丢弃。
     * key 为小时标签（"14时"），图表轴与点击明细直接展示。
     */
    fun hourlyBuckets(
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

    /** 明细行 → 新 rollup 行（首条入桶时调用；后续同键行在调用方累加） */
    fun rollupOf(dateStr: String, entry: UsageLogEntry): UsageRollup {
        return UsageRollup(
            date = dateStr, providerId = entry.providerId, providerName = entry.providerName,
            model = entry.model, kind = entry.kind,
            requestCount = 1, successCount = if (entry.statusCode in 200..299) 1L else 0L,
            inputTokens = entry.inputTokens, outputTokens = entry.outputTokens
        )
    }
}
