package com.wavex.agent.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.wavex.agent.model.TREE_ROOT
import com.wavex.agent.model.StoredMessage
import com.wavex.agent.model.TreeData
import com.wavex.agent.model.AgentConversationData
import com.wavex.agent.model.ConversationSnapshot

/**
 * 对话持久化：JSON 文件存 filesDir/conversations.json（分叉树格式）。
 * 每个对话存：nodes（全部消息节点，含各分支）+ children（父 id -> 子 id 列表）+
 * activeChild（父 id -> 当前激活子 id）；旧版平铺 messages 格式自动迁移为线性树。
 * 附件 URI 之前已 takePersistableUriPermission，重启后仍可读。
 * 保存时机：消息列表变更时防抖写入，避免流式输出每帧写盘。
 */
class ConversationStore internal constructor(private val file: java.io.File) {
    constructor(context: Context) : this(java.io.File(context.filesDir, "conversations.json"))

    private fun parseStoredMessage(m: JSONObject): StoredMessage {
        val attsJson = m.optJSONArray("attachments") ?: JSONArray()
        val atts = mutableListOf<Pair<String, String>>()
        for (k in 0 until attsJson.length()) {
            val a = attsJson.getJSONObject(k)
            atts.add(a.optString("uri") to a.optString("name"))
        }
        return StoredMessage(
            id = m.optString("id", ""),
            text = m.optString("text"),
            fromUser = m.optBoolean("fromUser"),
            isError = m.optBoolean("isError"),
            reasoning = m.optString("reasoning"),
            attachments = atts
        )
    }

    /** 旧格式（平铺消息列表）→ 线性树迁移 */
    internal fun linearToTree(msgs: List<StoredMessage>): TreeData {
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

    /** 加载全部对话（含旧格式自动迁移） */
    fun load(): List<ConversationSnapshot> {
        val result = mutableListOf<ConversationSnapshot>()
        val raw = try { file.readText() } catch (_: Exception) { null } ?: return result
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
                    result.add(ConversationSnapshot(conv.optString("id"), conv.optString("title", "新对话"), TreeData(nodes, children, activeChild)))
                } else {
                    // 旧格式：平铺消息 → 迁移成线性树
                    val msgsJson = conv.optJSONArray("messages") ?: JSONArray()
                    val msgs = mutableListOf<StoredMessage>()
                    for (j in 0 until msgsJson.length()) {
                        var m = parseStoredMessage(msgsJson.getJSONObject(j))
                        if (m.id.isBlank()) m = m.copy(id = java.util.UUID.randomUUID().toString())
                        msgs.add(m)
                    }
                    result.add(ConversationSnapshot(conv.optString("id"), conv.optString("title", "新对话"), linearToTree(msgs)))
                }
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(conversations: List<AgentConversationData>) {
        try {
            val arr = JSONArray()
            conversations.forEach { conv ->
                val nodesJson = JSONArray()
                conv.nodes.values.forEach { m ->
                    val mJson = JSONObject()
                        .put("id", m.id)
                        .put("text", m.text)
                        .put("fromUser", m.fromUser)
                        .put("isError", m.isError)
                    if (m.reasoning.isNotBlank()) mJson.put("reasoning", m.reasoning)
                    if (m.attachments.isNotEmpty()) {
                        val atts = JSONArray()
                        m.attachments.forEach { a ->
                            atts.put(JSONObject().put("uri", a.first).put("name", a.second))
                        }
                        mJson.put("attachments", atts)
                    }
                    nodesJson.put(mJson)
                }
                val childrenJson = JSONObject()
                conv.children.forEach { (parent, ids) -> childrenJson.put(parent, JSONArray(ids)) }
                val activeJson = JSONObject()
                conv.activeChild.forEach { (parent, id) -> activeJson.put(parent, id) }
                arr.put(
                    JSONObject()
                        .put("id", conv.id)
                        .put("title", conv.title)
                        .put("nodes", nodesJson)
                        .put("children", childrenJson)
                        .put("activeChild", activeJson)
                )
            }
            // 原子写入：先写临时文件再改名，进程在写盘中途被杀不会损坏 conversations.json
            // （旧实现直接 writeText，写一半被杀 = 全部历史丢失）
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                // 个别文件系统 rename 到已存在目标会失败：删旧文件后重试一次
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: Exception) {
            // 磁盘满等异常时静默失败，不打断聊天
        }
    }
}
