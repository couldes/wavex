package com.wavex.agent.core.data.usage

import com.wavex.agent.core.domain.model.UsageLogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 明细日志（usage_logs.json）持久化：整表 JSON 读/写 + 坏表备份。
 * 只做文件 IO，不做聚合、不持有 pending —— 记账循环（record/flush/防抖）在
 * UsageStore 门面（UsageSink 实现）内完成，全部调用都在门面的实例锁下。
 */
internal class UsageLogStore(private val logsFile: File) {

    // 「没读出来」不等于「用户删光了」：只有真正解析失败时才拦住空覆盖（对齐 ProviderStore 语义）
    var lastLoadFailed = false
        private set

    /** 整表读出（按 createdAt 升序）；坏表 → .corrupt 副本 + 空表。 */
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
            sorted
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

    /** 把该写回磁盘的行整表写入（tmp + 原子 rename，Windows 失败删旧重试一次）。 */
    fun writeLogs(entries: List<UsageLogEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
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
                //（抄漏了 file.delete() 会导致第二次写入永远不生效）
                logsFile.delete()
                if (!tmp.renameTo(logsFile)) tmp.delete()
            }
        } catch (_: Exception) {}
    }

    private fun putIfNotNull(o: JSONObject, key: String, value: Any?) {
        if (value == null) return
        when (value) { is String -> o.put(key, value); is Int -> o.put(key, value.toLong()); else -> o.put(key, value) }
    }
}
