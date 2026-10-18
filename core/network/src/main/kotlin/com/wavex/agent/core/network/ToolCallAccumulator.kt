package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * 流式工具调用的增量累积器。
 *
 * 两种协议的增量形状不同：
 * - OpenAI 把 `id`/`name` 放在首个分片，`arguments` 逐字符分片到达，按 `index` 归并；
 * - Anthropic 用 `content_block_start` 给 id/name，`input_json_delta` 追加
 *   `partial_json`，`content_block_stop` 收尾。
 *
 * [snapshot] 是非破坏性的：流式过程中上层会被多次回调，清空累积状态会让后续
 * 分片丢失。真正重置只发生在 [reset]（新一轮请求开始时）。
 */
internal class ToolCallAccumulator {

    private class Pending(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )

    private val pending = LinkedHashMap<Int, Pending>()

    /** Anthropic 的 content_block 序号（只有工具块才会进 map） */
    private var anthropicIndex = -1

    fun isEmpty(): Boolean = pending.values.all { it.name.isBlank() && it.args.isEmpty() }

    fun reset() {
        pending.clear()
        anthropicIndex = -1
    }

    /** 解析 OpenAI `choices[].delta.tool_calls[]` */
    fun onOpenAiDelta(root: JSONObject) {
        val choices = root.optJSONArray("choices") ?: return
        for (i in 0 until choices.length()) {
            val delta = choices.optJSONObject(i)?.optJSONObject("delta") ?: continue
            val calls = delta.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) {
                val tc = calls.optJSONObject(j) ?: continue
                val index = tc.optInt("index", j)
                val p = pending.getOrPut(index) { Pending() }
                tc.optString("id").takeIf { it.isNotEmpty() }?.let { p.id = it }
                val fn = tc.optJSONObject("function")
                fn?.optString("name")?.takeIf { it.isNotEmpty() }?.let { p.name = it }
                fn?.optString("arguments")?.takeIf { it.isNotEmpty() }?.let { p.args.append(it) }
            }
        }
    }

    /** Anthropic `content_block_start`：type=tool_use 时登记 id/name */
    fun onAnthropicStart(id: String, name: String, index: Int = 0) {
        anthropicIndex = index
        pending[index] = Pending(id = id, name = name)
    }

    /** Anthropic `content_block_delta`：input_json_delta 的 partial_json 分片 */
    fun onAnthropicDelta(partialJson: String) {
        val p = pending[anthropicIndex] ?: return
        p.args.append(partialJson)
    }

    fun onAnthropicStop() {
        anthropicIndex = -1
    }

    /** @return 已累积的调用（不可变副本，不清理内部状态） */
    fun snapshot(): List<ToolCall> = pending.entries
        .sortedBy { it.key }
        .mapNotNull { (_, p) ->
            // 只有 id 没有名字的分片是半截数据（连接中断），丢弃比让模型看到空工具名好
            if (p.name.isBlank()) return@mapNotNull null
            ToolCall(
                id = p.id.ifBlank { "call_${p.name}" },
                name = p.name,
                arguments = p.args.toString(),
                query = extractQuery(p.name, p.args.toString()),
            )
        }

    /** 只在 web_search 上尝试提取 query：其余工具的参数形态未知，不猜 */
    private fun extractQuery(name: String, args: String): String? {
        if (name != "web_search" || args.isBlank()) return null
        return try {
            JSONObject(args).optString("query").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 从一段完整的 tool_calls JSON 数组（非流式响应）解析，复用同一套 query 提取 */
        fun fromJsonArray(calls: JSONArray): List<ToolCall> {
            val out = ArrayList<ToolCall>(calls.length())
            for (i in 0 until calls.length()) {
                val tc = calls.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isBlank()) continue
                val args = fn.optString("arguments")
                out += ToolCall(
                    id = tc.optString("id").ifBlank { "call_$name" },
                    name = name,
                    arguments = args,
                    query = if (name == "web_search" && args.isNotBlank()) {
                        try {
                            JSONObject(args).optString("query").takeIf { it.isNotBlank() }
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    },
                )
            }
            return out
        }
    }
}
