package com.wavex.agent.core.data

import org.json.JSONArray
import org.json.JSONObject
import com.wavex.agent.core.domain.model.TREE_ROOT
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.TreeData
import com.wavex.agent.core.domain.model.ConversationSnapshot

// 旧成功态占位迁移：占位已从正文删除（见 engine.ResponseImageExtractor），存量消息加载时清理。
// 仅匹配独占一行的 [图片] / [附件 名]；失败态（保存失败/过大/中断）与行中同形文本不匹配，防误伤真实正文
private val RE_LEGACY_IMG_LINE = Regex("(?m)^[ \\t]*\\[图片\\][ \\t]*\\n?")
private val RE_LEGACY_FILE_LINE = Regex("(?m)^[ \\t]*\\[附件 [^\\]\\n]*\\][ \\t]*\\n?")

/**
 * 旧 conversations.json 解析（parse/parseStoredMessage/linearToTree/legacy 正则自已删除的
 * 旧持久化类整体搬移，行为零改动；本对象为唯一实现）。
 * 迁移 LegacyMigrator / 一次性导入一律走本对象。
 */
object LegacyConversationJson {

    /** 存量消息迁移：带附件的消息里独占一行的旧成功态占位（[图片]/[附件 名]）删行清理；
     *  无附件的消息不可能是占位产物，原样返回。清理后的文本随下次保存自然持久化。 */
    private fun legacyPlaceholderCleanup(m: StoredMessage): StoredMessage {
        if (m.attachments.isEmpty()) return m
        val cleaned = RE_LEGACY_FILE_LINE
            .replace(RE_LEGACY_IMG_LINE.replace(m.text, ""), "")
            .trim()
        return if (cleaned == m.text) m else m.copy(text = cleaned)
    }

    private fun parseStoredMessage(m: JSONObject): StoredMessage {
        val attsJson = m.optJSONArray("attachments") ?: JSONArray()
        val atts = mutableListOf<Pair<String, String>>()
        for (k in 0 until attsJson.length()) {
            val a = attsJson.getJSONObject(k)
            atts.add(a.optString("uri") to a.optString("name"))
        }
        return legacyPlaceholderCleanup(
            StoredMessage(
                id = m.optString("id", ""),
                text = m.optString("text"),
                fromUser = m.optBoolean("fromUser"),
                isError = m.optBoolean("isError"),
                reasoning = m.optString("reasoning"),
                attachments = atts
            )
        )
    }

    /** 旧格式（平铺消息列表）→ 线性树迁移 */
    private fun linearToTree(msgs: List<StoredMessage>): TreeData {
        val nodes = LinkedHashMap<String, StoredMessage>()
        val children = LinkedHashMap<String, MutableList<String>>()
        val activeChild = LinkedHashMap<String, String>()
        var parent = TREE_ROOT
        msgs.forEach { m ->
            nodes[m.id] = m
            children.getOrPut(parent) { mutableListOf() }.add(m.id)
            activeChild[parent] = m.id
            parent = m.id
        }
        return TreeData(nodes, children, activeChild)
    }

    /** 解析持久化 JSON（新格式分叉树 + 旧格式平铺自动迁移）；坏数据静默返回空列表 */
    fun parse(raw: String): List<ConversationSnapshot> {
        val result = mutableListOf<ConversationSnapshot>()
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val conv = arr.getJSONObject(i)
                if (conv.has("nodes")) {
                    // 新格式：分叉树
                    val nodesJson = conv.getJSONArray("nodes")
                    val nodes = LinkedHashMap<String, StoredMessage>()
                    for (j in 0 until nodesJson.length()) {
                        var m = parseStoredMessage(nodesJson.getJSONObject(j))
                        if (m.id.isBlank()) m = m.copy(id = java.util.UUID.randomUUID().toString())
                        nodes[m.id] = m
                    }
                    val children = LinkedHashMap<String, List<String>>()
                    val childrenJson = conv.optJSONObject("children") ?: JSONObject()
                    val it = childrenJson.keys()
                    while (it.hasNext()) {
                        val parent = it.next()
                        val ids = mutableListOf<String>()
                        val arr2 = childrenJson.optJSONArray(parent) ?: JSONArray()
                        for (k in 0 until arr2.length()) ids.add(arr2.optString(k))
                        children[parent] = ids
                    }
                    val activeChild = LinkedHashMap<String, String>()
                    val activeJson = conv.optJSONObject("activeChild") ?: JSONObject()
                    val it2 = activeJson.keys()
                    while (it2.hasNext()) {
                        val parent = it2.next()
                        activeChild[parent] = activeJson.optString(parent)
                    }
                    result.add(
                        ConversationSnapshot(
                            conv.optString("id"), conv.optString("title", "新对话"),
                            conv.optBoolean("titleUserDefined", false),
                            conv.optInt("titleUserCount", 0),
                            TreeData(nodes, children, activeChild)
                        )
                    )
                } else {
                    // 旧格式：平铺消息 → 迁移成线性树
                    val msgsJson = conv.optJSONArray("messages") ?: JSONArray()
                    val msgs = mutableListOf<StoredMessage>()
                    for (j in 0 until msgsJson.length()) {
                        var m = parseStoredMessage(msgsJson.getJSONObject(j))
                        if (m.id.isBlank()) m = m.copy(id = java.util.UUID.randomUUID().toString())
                        msgs.add(m)
                    }
                    result.add(
                        ConversationSnapshot(
                            conv.optString("id"), conv.optString("title", "新对话"),
                            conv.optBoolean("titleUserDefined", false),
                            conv.optInt("titleUserCount", 0),
                            linearToTree(msgs)
                        )
                    )
                }
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }
}
