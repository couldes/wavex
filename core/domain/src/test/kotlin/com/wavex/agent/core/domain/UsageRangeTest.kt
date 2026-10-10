package com.wavex.agent.core.domain

import com.wavex.agent.core.domain.model.UsageRange
import com.wavex.agent.core.domain.model.clampCustomRange
import com.wavex.agent.core.domain.model.resolveToMillis
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 统计页日期范围的域层行为钉子：
 * - 预设「今日」= 本地时区闭区间 [当天 00:00:00.000, 23:59:59.999]；
 * - clampCustomRange：end < start 先交换；跨度超 maxDays 保 end、start 收紧。
 */
class UsageRangeTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 9)

    private fun startOf(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun endOf(d: LocalDate): Long = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    @Test
    fun `today is closed local-day interval`() {
        val (start, end) = UsageRange.Today.resolveToMillis(today, zone)
        assertEquals(startOf(today), start)
        assertEquals(endOf(today), end)
        // 闭区间右端：当天 23:59:59.999（毫秒位 999）
        val endOfDay = Instant.ofEpochMilli(end).atZone(zone)
        assertEquals(23, endOfDay.hour)
        assertEquals(59, endOfDay.minute)
        assertEquals(59, endOfDay.second)
        assertEquals(999, endOfDay.nano / 1_000_000)
        val nextMs = end + 1
        assertEquals(startOf(today.plusDays(1)), nextMs)
    }

    @Test
    fun `custom reversed endpoints are swapped`() {
        val early = startOf(today)
        val late = endOf(today.plusDays(2))
        val (s, e) = clampCustomRange(late, early, zone = zone)   // (晚, 早) 乱序传入
        assertEquals(early, s)
        assertEquals(late, e)
    }

    @Test
    fun `range exceeding maxDays keeps end and clamps start`() {
        val start = startOf(today)
        val end = endOf(today.plusDays(60))   // 跨度 61 天 > maxDays=30
        val (s, e) = clampCustomRange(start, end, maxDays = 30, zone = zone)
        assertEquals(end, e)   // 保 end
        // start 收到 end 往前 (30-1)=29 天的 00:00：钳制后跨度恰为 30 天
        assertEquals(startOf(today.plusDays(31)), s)
        val spanDays = java.time.temporal.ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(s).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(e).atZone(zone).toLocalDate()
        ) + 1
        assertEquals(30, spanDays)
    }
}
