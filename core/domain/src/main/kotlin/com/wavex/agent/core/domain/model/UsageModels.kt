package com.wavex.agent.core.domain.model

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

/** 统计页一屏查询结果：单次读盘解析同时产出五项（概览/服务商/模型/趋势/最近日志） */
data class UsagePageSnapshot(
    val summary: UsageSummary,
    val providerStats: List<ProviderStat>,
    val modelStats: List<ModelStat>,
    val trends: List<TrendPoint>,
    val recentLogs: List<UsageLogEntry>
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

// ---------- 日期范围（纯函数，JVM 可测） ----------

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
