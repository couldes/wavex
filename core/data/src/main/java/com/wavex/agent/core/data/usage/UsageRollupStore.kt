package com.wavex.agent.core.data.usage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
