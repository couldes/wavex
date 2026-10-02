package com.wavex.agent.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UsageStore 测试：纯 JVM。
 * Task 1：roundtrip / 坏文件回退 / 异常吞掉。
 * Task 2：滚动聚合 / 水位线防双计（崩溃模拟）/ 剪除边界。
 */
class UsageStoreTest {

    private fun createTempStore(): Triple<UsageStore, File, File> {
        val stamp = System.nanoTime()   // nanoTime 避免同毫秒撞名
        val logs = File(System.getProperty("java.io.tmpdir"), "usage_logs_test_$stamp.json")
        val rollups = File(System.getProperty("java.io.tmpdir"), "usage_rollups_test_$stamp.json")
        val store = UsageStore(logs, rollups)
        store.debounceMs = 0L   // plan 契约：测试走同步落盘，避免后台 debounce 干扰时序
        return Triple(store, logs, rollups)
    }

    private fun entry(id: String, createdAt: Long, code: Int = 200, input: Long = 100, output: Long = 50) =
        UsageLogEntry(id, "chat", "p1", "DeepSeek", "deepseek-chat", input, output, code, 100L, createdAt, "")

    private fun readRollups(file: File): UsageRollupsFile =
        UsageRollupsFile(file).apply { load() }

    // ---------- Task 1 ----------

    @Test
    fun `record 后 flush 落盘且 load 往返完整`() {
        val (store, logsFile, _) = createTempStore()
        val e = entry("id1", System.currentTimeMillis())

        store.record(e)
        store.flush()

        assertTrue("logs file should exist after flush", logsFile.exists())
        assertTrue("logs file should not be empty", logsFile.length() > 0)

        val loaded = store.loadLogs()
        assertEquals(1, loaded.size)
        with(loaded[0]) {
            assertEquals("id1", id)
            assertEquals("chat", kind)
            assertEquals("DeepSeek", providerName)
            assertEquals(100L, inputTokens)
            assertEquals(200, statusCode)
        }
    }

    @Test
    fun `pageData 与分项查询同口径且单次出全五项`() {
        val (store, _, _) = createTempStore()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val now = System.currentTimeMillis()

        store.record(entry("a", now, code = 200, input = 10, output = 5))
        store.record(entry("b", now, code = 500, input = 20, output = 8))
        // 标题请求：落明细日志但不计入统计口径
        store.record(UsageLogEntry("t1", "title", "p1", "DeepSeek", "deepseek-chat", 1, 2, 200, 10L, now, ""))
        store.flush()

        val snap = store.pageData(start, end, null)
        // 概览：只计 chat，成功只数 2xx
        assertEquals(2L, snap.summary.requests)
        assertEquals(1L, snap.summary.success)
        assertEquals(30L, snap.summary.inputTokens)
        assertEquals(13L, snap.summary.outputTokens)
        assertEquals(1, snap.providerStats.size)
        assertEquals("DeepSeek", snap.providerStats[0].providerName)
        assertEquals(1, snap.modelStats.size)
        // 单日区间 → 小时桶（24 点），请求全落在今天
        assertEquals(24, snap.trends.size)
        assertEquals(2L, snap.trends.sumOf { it.requests })
        // 最近日志：全类型可见（含 title）
        assertEquals(3, snap.recentLogs.size)

        // 服务商筛选：统计口径随筛选，但 providerStats 仍全量（筛选 chips 所见即可筛）
        val filtered = store.pageData(start, end, "p1")
        assertEquals(2L, filtered.summary.requests)
        assertEquals(1, filtered.providerStats.size)
        assertEquals(2L, filtered.providerStats[0].requests)
        assertEquals(0L, store.pageData(start, end, "p2").summary.requests)
    }

    @Test
    fun `损坏的明细文件回退空表且保留 corrupt 副本`() {
        val (_, logs, rollupsFile) = createTempStore()
        logs.writeText("not json{{{{")

        val newStore = UsageStore(logs, rollupsFile)
        val loaded = newStore.loadLogs()
        assertEquals(0, loaded.size)

        val backup = File(logs.parent, "${logs.name}.corrupt")
        assertTrue("Backup file should exist", backup.exists())
        assertTrue("Backup should contain original content", backup.readText().startsWith("not"))
    }

    @Test
    fun `坏文件存在时无新数据不覆盖 有新数据照写不丢`() {
        val (store, logs, rollupsFile) = createTempStore()
        logs.writeText("损坏数据")
        val corruptedContent = logs.readText()

        // 场景 1：坏文件 + 无新数据 → 拒绝覆盖（corrupt 原文保留、主文件不动）
        store.flush()
        val backup = File(logs.parent, "${logs.name}.corrupt")
        assertTrue("Backup should exist", backup.exists())
        assertEquals("Backup keeps original", corruptedContent, backup.readText())
        assertEquals("Main file untouched", corruptedContent, logs.readText())

        // 场景 2：坏文件 + 新记录 → 照常写入（新数据不丢），corrupt 副本仍在
        store.record(entry("new-id", System.currentTimeMillis()))
        store.flush()
        assertTrue("New data survives", store.loadLogs().any { it.id == "new-id" })
        assertTrue("Corrupt backup still there", backup.exists())
    }

    @Test
    fun `record 抛异常不影响返回`() {
        val (store, _, _) = createTempStore()
        var threw = false
        try {
            store.record(entry("x", System.currentTimeMillis()))
            store.flush()
        } catch (e: Exception) {
            threw = true
        }
        assertEquals(false, threw)
        assertEquals(1, store.loadLogs().size)
    }

    // ---------- Task 2 ----------

    @Test
    fun `超过 90 天的旧行并入 rollups 并从明细剪除`() {
        val (store, _, rollupsFile) = createTempStore()

        // 91 天前的旧行（watermark 从 0 起算 → 必被合并）
        val oldTime = System.currentTimeMillis() - (91L * 24 * 60 * 60 * 1000L)
        store.record(entry("old-id", oldTime))
        store.flush()

        // 旧行已入 rollups
        val r = readRollups(rollupsFile)
        assertEquals(1, r.rows.sumOf { it.requestCount })

        // 今天的新行仍在明细
        store.record(entry("new-id", System.currentTimeMillis()))
        store.flush()
        val details = store.loadLogs()
        assertEquals(1, details.size)
        assertEquals("new-id", details[0].id)
    }

    @Test
    fun `水位线重跑不双计——模拟崩溃后rollups已写明细未剪`() {
        val (store, logs, rollupsFile) = createTempStore()

        // 第一步：正常滚动（旧行进 rollups、明细剪除）
        val oldTime = System.currentTimeMillis() - (91L * 24 * 60 * 60 * 1000L)
        store.record(entry("old-id", oldTime, input = 100))
        store.flush()

        // 第二步：模拟崩溃——rollups 已写，明细剪除步骤没跑（手工把旧行写回明细文件）
        val corruptSim = JSONArray().put(
            JSONObject()
                .put("id", "old-id").put("kind", "chat").put("providerId", "p1")
                .put("providerName", "DeepSeek").put("model", "deepseek-chat")
                .put("inputTokens", 100).put("outputTokens", 50)
                .put("statusCode", 200).put("latencyMs", 100).put("createdAt", oldTime)
                .put("error", "")
        )
        logs.writeText(corruptSim.toString())

        // 第三步：再来一次正常 save（新行触发）
        store.record(entry("new-id", System.currentTimeMillis()))
        store.flush()

        // 断言：旧行没有被二次合并（requestCount 仍为 1），且明细里不再有旧行
        val r = readRollups(rollupsFile)
        assertEquals(1, r.rows.sumOf { it.requestCount })

        val details = store.loadLogs()
        assertEquals(1, details.size)
        assertEquals("new-id", details[0].id)
    }

    @Test
    fun `90 天边界当天不剪除`() {
        val (store, _, rollupsFile) = createTempStore()

        val boundaryTime = System.currentTimeMillis() - (89L * 24 * 60 * 60 * 1000L)
        store.record(entry("boundary-id", boundaryTime))
        store.flush()

        // 89 天前 > 90 天 cutoff → 留在明细、不进 rollups
        assertEquals(1, store.loadLogs().size)
        assertEquals(0, readRollups(rollupsFile).rows.sumOf { it.requestCount })
    }

    @Test
    fun `successCount 只数 2xx`() {
        val (store, _, rollupsFile) = createTempStore()

        // 两条 100 天前的行：200 成功 + 500 失败（用聚合和断言，避免跨天分桶影响行数）
        val base = System.currentTimeMillis() - (100L * 24 * 60 * 60 * 1000L)
        store.record(entry("a", base, code = 200, input = 100, output = 50))
        store.record(entry("b", base + 60_000, code = 500, input = 100, output = 50))
        store.flush()

        val r = readRollups(rollupsFile)
        assertEquals(2, r.rows.sumOf { it.requestCount })
        assertEquals(1, r.rows.sumOf { it.successCount })
        assertEquals(200, r.rows.sumOf { it.inputTokens })
        assertEquals(100, r.rows.sumOf { it.outputTokens })

        // 两条都超过 90 天 → 明细剪空
        assertEquals(0, store.loadLogs().size)
    }

    // ---------- Task 3: 聚合查询 ----------

    private fun makeRollup(
        date: String, providerId: String = "p1", providerName: String = "DeepSeek",
        model: String = "deepseek-chat", kind: String = "chat",
        requests: Long = 1, success: Long = 1, input: Long = 100, output: Long = 50
    ) = UsageRollup(date, providerId, providerName, model, kind, requests, success, input, output)

    @Test
    fun `混合 rollups 与明细的 summary 不双计`() {
        val (store, _, rollupsFile) = createTempStore()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)

        // rollup：91 天前的整天（Task 2 滚动逻辑自然产生）
        val oldDay = today.minusDays(91)
        val oldTime = oldDay.atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli()
        store.record(entry("old-1", oldTime, input = 100, output = 50))
        store.flush()

        // 明细：今天一行
        store.record(entry("new-1", System.currentTimeMillis(), input = 200, output = 100))
        store.flush()

        // 范围覆盖 rollup 天 + 今天 → 两部分相加，各算一次
        val start = oldDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        val s = store.summary(start, end)
        assertEquals(2, s.requests)
        assertEquals(2, s.success)
        assertEquals(300L, s.inputTokens)
        assertEquals(150L, s.outputTokens)
    }

    @Test
    fun `跨界日由明细贡献 rollup 整天不再计入`() {
        val (store, _, rollupsFile) = createTempStore()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)

        // 手工注入 rollup：date = 今天（与明细同一"跨界日"）
        store.reloadRollups()
        UsageRollupsFile(rollupsFile).apply {
            addAll(listOf(makeRollup(today.toString(), requests = 5, input = 500, output = 250)))
            save()
        }
        store.reloadRollups()

        // 明细：今天 10:00 一行
        val detailTime = today.atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli()
        store.record(entry("d-1", detailTime, input = 100, output = 50))
        store.flush()

        // 区间 = 今天整天：rollup 整天完整落在区间内，但 dayEnd > 最早明细 → 不计入
        // 只有明细行被统计 → 不双计
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val s = store.summary(start, end)
        assertEquals(1, s.requests)
        assertEquals(100L, s.inputTokens)
    }

    @Test
    fun `23点59与次日0点分属两天`() {
        val (store, _, _) = createTempStore()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val lastMoment = today.atStartOfDay(zone).plusHours(23).plusMinutes(59).plusSeconds(59)
            .plusNanos((999_999_999).toLong() / 1_000_000).toInstant().toEpochMilli()
        val nextStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        store.record(entry("a", lastMoment))
        store.record(entry("b", nextStart))
        store.flush()

        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).plusHours(1).toInstant().toEpochMilli()
        val trends = store.dailyTrends(start, end)
        assertEquals(2, trends.size)          // 两天
        assertEquals(1, trends[0].requests)   // 各 1 次
        assertEquals(1, trends[1].requests)
        assertEquals(today.toString(), trends[0].key)
    }

    // ---------- 趋势粒度（cc-switch 口径：单日按小时 24 点，跨日按天） ----------

    /** 当天某时某分的毫秒时间戳（本地时区） */
    private fun at(zone: java.time.ZoneId, today: java.time.LocalDate, hour: Int, minute: Int = 0): Long =
        today.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    @Test
    fun `trends 单日区间走小时桶 跨日走日桶`() {
        val (store, _, _) = createTempStore()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        store.record(entry("a", at(zone, today, 9)))
        store.flush()

        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        // 同一本地日 → 小时桶：固定 24 点，9时命中
        val hourly = store.trends(dayStart, dayEnd)
        assertEquals(24, hourly.size)
        assertEquals(1, hourly[9].requests)
        assertEquals("9时", hourly[9].key)
        assertEquals(0, hourly[8].requests)

        // 跨日 → 日桶
        val daily = store.trends(dayStart, dayEnd + 3_600_000)
        assertEquals(2, daily.size)
    }

    @Test
    fun `hourlyBuckets 按本地小时分桶并补零`() {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        val buckets = UsageStore.hourlyBuckets(
            listOf(entry("a", at(zone, today, 13, 59)), entry("b", at(zone, today, 14, 30), input = 30)),
            start, end, zone
        )

        assertEquals(24, buckets.size)
        assertEquals("0时", buckets[0].key)
        assertEquals(1, buckets[13].requests)
        assertEquals(100L, buckets[13].inputTokens)
        assertEquals(50L, buckets[13].outputTokens)
        assertEquals(1, buckets[14].requests)
        assertEquals(30L, buckets[14].inputTokens)
        assertEquals(0, buckets[0].requests)
    }

    @Test
    fun `hourlyBuckets 过滤区间外记录`() {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        // 明天与昨天的记录都不进今天的小时桶
        val buckets = UsageStore.hourlyBuckets(
            listOf(entry("a", at(zone, today.plusDays(1), 5)), entry("b", at(zone, today.minusDays(1), 5))),
            start, end, zone
        )

        assertEquals(24, buckets.size)
        assertTrue(buckets.all { it.requests == 0L && it.inputTokens == 0L && it.outputTokens == 0L })
    }

    @Test
    fun `provider 与 kind 筛选同时生效`() {
        val (store, _, _) = createTempStore()
        val now = System.currentTimeMillis()
        fun e(id: String, pid: String, pname: String, kind: String) =
            UsageLogEntry(id, kind, pid, pname, "model-x", 10, 5, 200, 1, now, "")
        store.record(e("1", "p1", "DeepSeek", "chat"))
        store.record(e("2", "p1", "DeepSeek", "title"))
        store.record(e("3", "p2", "GLM", "chat"))
        store.record(e("4", "p2", "GLM", "chat"))
        store.flush()

        // provider=p1 → 2 条；再 kind=chat → 1 条
        val s = store.summary(now - 1, now + 1, providerId = "p1", kind = "chat")
        assertEquals(1, s.requests)

        val byProvider = store.providerStats(now - 1, now + 1)
        assertEquals(2, byProvider.size)      // 两个 provider
        // 统计口径只计 chat：p1 的 title 行不计数（p1=1、p2=2）
        assertEquals(3, byProvider.sumOf { it.requests })
        assertEquals(1, byProvider.first { it.providerId == "p1" }.requests)
        // provider 筛选
        val glm = store.providerStats(now - 1, now + 1, kind = "chat").first { it.providerId == "p2" }
        assertEquals(2, glm.requests)         // p2 全是 chat
    }

    @Test
    fun `dailyTrends 无数据日补零且升序`() {
        val (store, _, _) = createTempStore()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)

        // 区间 5 天，只有第 3 天有数据
        val day3 = today.minusDays(2).atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
        store.record(entry("mid", day3))
        store.flush()

        val start = today.minusDays(4).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val trends = store.dailyTrends(start, end)

        assertEquals(5, trends.size)          // 5 天连续
        assertEquals(0, trends[0].requests)   // 首 2 天补零
        assertEquals(0, trends[1].requests)
        assertEquals(1, trends[2].requests)   // 第 3 天有值
        assertEquals(0, trends[3].requests)
        assertEquals(0, trends[4].requests)
        // 升序
        assertTrue(trends.zipWithNext().all { (a, b) -> a.key < b.key })
    }

    @Test
    fun `recentLogs 按时间倒序并截断 limit`() {
        val (store, _, _) = createTempStore()
        val now = System.currentTimeMillis()
        (1..5).forEach { store.record(entry("id$it", now - (6 - it) * 1000L)) }
        store.flush()

        val logs = store.recentLogs(now - 10_000, now + 10_000, limit = 3)
        assertEquals(3, logs.size)
        assertEquals(listOf("id5", "id4", "id3"), logs.map { it.id })  // 倒序取最新
    }

    // ---------- Task 8: 日期范围解析 ----------

    private val zone = java.time.ZoneId.of("Asia/Shanghai")
    private val today = java.time.LocalDate.of(2026, 1, 15)

    private fun ms(d: java.time.LocalDate, hour: Long = 0) =
        d.atStartOfDay(zone).plusHours(hour).toInstant().toEpochMilli()

    private fun endOf(d: java.time.LocalDate) =
        d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    @Test
    fun `五种预设的解析边界`() {
        assertEquals(Pair(ms(today), endOf(today)),
            (UsageRange.Today as UsageRange).resolveToMillis(today, zone))
        assertEquals(Pair(ms(today.minusDays(1)), endOf(today.minusDays(1))),
            (UsageRange.Yesterday as UsageRange).resolveToMillis(today, zone))
        assertEquals(Pair(ms(today.minusDays(6)), endOf(today)),
            (UsageRange.Last7Days as UsageRange).resolveToMillis(today, zone))
        assertEquals(Pair(ms(today.minusDays(29)), endOf(today)),
            (UsageRange.Last30Days as UsageRange).resolveToMillis(today, zone))
        assertEquals(Pair(ms(today.withDayOfMonth(1)), endOf(today.withDayOfMonth(today.lengthOfMonth()))),
            (UsageRange.ThisMonth as UsageRange).resolveToMillis(today, zone))
    }

    @Test
    fun `自定义跨度超 30 天被钳制且保 end`() {
        // start = 40 天前，end = 今天 → 钳到 [end-29天 00:00, end]
        val end = endOf(today)
        val start = ms(today.minusDays(40))
        val (s2, e2) = clampCustomRange(start, end)
        assertEquals(end, e2)
        assertEquals(ms(today.minusDays(29)), s2)
    }

    @Test
    fun `自定义 end 早于 start 被交换`() {
        val early = ms(today.minusDays(3))
        val late = ms(today)
        val (s2, e2) = clampCustomRange(late, early)   // (晚, 早) 乱序传入
        assertEquals(early, s2)                        // 交换后 start = 早值
        assertEquals(late, e2)                         // end = 晚值
    }

    // ---------- 统计口径只计对话：标题/探测落明细日志（最近请求可见）但不计入统计 ----------

    @Test
    fun `辅助请求落日志但不计入统计`() {
        val (store, _, _) = createTempStore()
        val now = System.currentTimeMillis()
        // 模拟存量辅助数据：探测失败一条（gpt-4o-mini 场景）+ 标题一条
        val probeFail = UsageLogEntry("pr1", "probe", "p2", "OpenAI", "gpt-4o-mini",
            0, 0, 401, 120L, now + 2, "HTTP 401")
        val title = UsageLogEntry("t1", "title", "p1", "DeepSeek", "deepseek-chat",
            5, 3, 200, 80L, now + 1, "")
        store.record(entry("c1", now))
        store.record(title)
        store.record(probeFail)
        store.flush()

        val range = (now - 1_000L) to (now + 1_000L)
        // 统计只剩 chat
        assertEquals(1, store.summary(range.first, range.second).requests)
        assertEquals(listOf("p1"), store.providerStats(range.first, range.second).map { it.providerId })
        assertEquals(1, store.modelStats(range.first, range.second).sumOf { it.requests })
        assertEquals(1, store.trends(range.first, range.second).sumOf { it.requests })  // 单日小时桶路径
        // 最近请求日志全类型可见（时间倒序）
        val logs = store.recentLogs(range.first, range.second)
        assertEquals(3, logs.size)
        assertEquals(listOf("probe", "title", "chat"), logs.map { it.kind })
    }
}
