package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.ToolCall
import com.wavex.agent.core.domain.model.TREE_ROOT
import com.wavex.agent.core.domain.model.TreeData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话 JSONL 编解码（spec §4.2 行格式）：每行一个 JSON 对象，五类行 ——
 * session / message / session_info / active / delete。追加写入方按行落盘，
 * parse() 按折叠规则（spec §4.3）把整个文件重建为纯数据快照。
 * org.json 逐字段 put；空 reasoning / 空 attachments 不写字段（省行宽），
 * 解析端按缺省值容忍（缺字段取类型默认值）。
 */
internal object SessionCodec {
    const val FORMAT_VERSION = 1

    /** 文件首行：会话身份（会话 id 以 header 的 id 为准） */
    fun buildSessionHeader(id: String, timestamp: String): String =
        JSONObject()
            .put("type", "session")
            .put("version", FORMAT_VERSION)
            .put("id", id)
            .put("timestamp", timestamp)
            .toString()

    /** message 行：entryId 为行自身的 id（UUID）；parentId = null 表示根消息 */
    fun buildMessageEntry(entryId: String, parentId: String?, timestamp: String, m: StoredMessage): String {
        val msg = JSONObject()
            .put("text", m.text)
            .put("fromUser", m.fromUser)
            .put("isError", m.isError)
        if (m.reasoning.isNotBlank()) msg.put("reasoning", m.reasoning)
        if (m.attachments.isNotEmpty()) {
            val arr = JSONArray()
            m.attachments.forEach { (uri, name) ->
                arr.put(JSONObject().put("uri", uri).put("name", name))
            }
            msg.put("attachments", arr)
        }
        // 工具调用只在存在时写：普通消息不背这两个字段（省行宽，也保持旧文件形状）
        if (m.toolCalls.isNotEmpty()) {
            val arr = JSONArray()
            m.toolCalls.forEach { c ->
                // 不写 query：它是从 arguments 派生的，解析端重新提取，避免两份真相
                arr.put(JSONObject().put("id", c.id).put("name", c.name).put("arguments", c.arguments))
            }
            msg.put("toolCalls", arr)
        }
        m.toolCallId?.let { msg.put("toolCallId", it) }
        return JSONObject()
            .put("type", "message")
            .put("id", entryId)
            // null 根消息用 JSONObject.NULL 原生表达（字符串 "null" 会破坏解析端判空）
            .put("parentId", parentId ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("message", msg)
            .toString()
    }

    /** 标题三元组覆盖行（手动改名 / 演化后追加）；随行携带会话活跃/置顶/归档元数据（缺省不写） */
    fun buildSessionInfo(
        timestamp: String,
        name: String,
        userDefined: Boolean,
        userCount: Int,
        lastActiveAt: Long = 0,
        pinned: Boolean = false,
        pinnedAt: Long = 0,
        archived: Boolean = false,
    ): String {
        val o = JSONObject()
            .put("type", "session_info")
            .put("timestamp", timestamp)
            .put("name", name)
            .put("userDefined", userDefined)
            .put("userCount", userCount)
        if (lastActiveAt > 0) o.put("lastActive", lastActiveAt)
        if (pinned) o.put("pinned", true)
        if (pinnedAt > 0) o.put("pinnedAt", pinnedAt)
        if (archived) o.put("archived", true)
        return o.toString()
    }

    /** 活跃分支切换行：targetId 为消息 id */
    fun buildActive(timestamp: String, targetId: String): String =
        JSONObject()
            .put("type", "active")
            .put("timestamp", timestamp)
            .put("targetId", targetId)
            .toString()

    /** 删除行：targetId 为消息 id */
    fun buildDelete(timestamp: String, targetId: String): String =
        JSONObject()
            .put("type", "delete")
            .put("timestamp", timestamp)
            .put("targetId", targetId)
            .toString()

    /**
     * 快照 → 完整初始行集（旧数据迁移与导入共用）：header + 按 nodes 顺序的 message 行 +
     * active 行 + session_info，行序即文件序。
     * - entryId = 消息 id 本身：parse 折叠以行 id 为节点 id（spec §4.3 upsert 以同 id 为键），
     *   round-trip 逐字段还原原树的 nodes/children/activeChild（消息 id 原样保留）；
     * - parentId 由 children 反查（同 id 多桶首桶为准，SessionDiff 同规）；根消息 → null；
     *   不在任何桶的孤儿节点（旧 UI 不进任何路径、不可见）跳过不落行，保持不可见；
     * - active 行只为「activeChild[p] ≠ 折叠默认」的 parent 补发 —— 折叠默认 = p 在文件序中
     *   最后挂上的孩子（parse 端新节点注册即成为默认活跃），首挂孩子即活跃的 parent 无需行；
     * - 时间戳取单次 now()（迁移/导入时刻），全行共用。
     */
    fun initialLines(snapshot: ConversationSnapshot, idOverride: String? = null,
                     now: () -> String): List<String> {
        val timestamp = now()
        val lines = mutableListOf(buildSessionHeader(idOverride ?: snapshot.id, timestamp))
        val tree = snapshot.tree
        // parentId 反查表（同 id 出现在多个桶的脏数据：首桶为准）
        val parentOf = HashMap<String, String>()
        tree.children.forEach { (parent, ids) -> ids.forEach { id -> parentOf.putIfAbsent(id, parent) } }
        // 折叠默认活跃孩子：每个 parent 在文件序中最后挂上的孩子
        val foldDefault = HashMap<String, String>()
        tree.nodes.values.forEach { m ->
            val parent = parentOf[m.id] ?: return@forEach  // 孤儿节点不落行（保持不可见）
            val parentArg = parent.takeUnless { it == TREE_ROOT }
            lines += buildMessageEntry(m.id, parentArg, timestamp, m)
            foldDefault[parent] = m.id
        }
        tree.activeChild.forEach { (parent, active) ->
            if (foldDefault[parent] != active) lines += buildActive(timestamp, active)
        }
        lines += buildSessionInfo(timestamp, snapshot.title, snapshot.titleUserDefined, snapshot.titleUserCount,
            snapshot.lastActiveAt, snapshot.pinned, snapshot.pinnedAt, snapshot.archived)
        return lines
    }

    /** 折叠结果：会话身份 + 标题三元组 + 活跃/置顶/归档元数据 + 纯数据树 */
    data class Parsed(
        val id: String, val title: String,
        val titleUserDefined: Boolean, val titleUserCount: Int,
        val lastActiveAt: Long = 0,
        val pinned: Boolean = false,
        val pinnedAt: Long = 0,
        val archived: Boolean = false,
        val tree: TreeData
    ) {
        fun toSnapshot(): ConversationSnapshot = ConversationSnapshot(
            id = id, title = title,
            titleUserDefined = titleUserDefined, titleUserCount = titleUserCount,
            lastActiveAt = lastActiveAt, pinned = pinned, pinnedAt = pinnedAt,
            archived = archived,
            tree = tree,
        )
    }

    /**
     * 解析整个 .jsonl 内容；无 header → null；空树（header-only / 消息全被删）照常解析为
     * 零节点会话 —— 改名空草稿、消息删空的历史会话必须可加载，空会话的丢弃由加载下游
     * 负责（spec §6.2 规则 2：drop + 删文件，fix round 1）。
     * 残行 / 未知 type / 字段损坏的行静默跳过（spec §4.4）。
     *
     * 折叠规则（spec §4.3）：message upsert（同 id 整行覆盖，parentId 变化时从旧父孩子表移除、
     * 挂新父末尾）；delete 删节点、直接孩子改签给 target 的父并保持相对顺序、活跃链取被删节点
     * 原活跃孩子（对齐 AgentConversation.removeMessageById）；active → activeChild[parent] = target
     * （parent 按当时树状态取）；session_info 覆盖标题三元组。节点每次挂到某父末尾即成为该父的
     * 默认活跃孩子，active 行随后覆盖 —— 无 active 行时各 parent 的活跃孩子 = 文件序最后一个孩子。
     */
    fun parse(raw: String): Parsed? {
        var id: String? = null
        var title = ""
        var titleUserDefined = false
        var titleUserCount = 0
        var lastActiveAt = 0L
        var pinned = false
        var pinnedAt = 0L
        var archived = false
        val nodes = LinkedHashMap<String, StoredMessage>()
        val children = LinkedHashMap<String, MutableList<String>>()
        val activeChild = LinkedHashMap<String, String>()
        // 折叠期父指针：active / delete 行按「当时树状态」取父
        val parentOf = HashMap<String, String>()

        fun childListOf(parentId: String): MutableList<String> =
            children.getOrPut(parentId) { mutableListOf() }

        fun fold(line: String) {
            val o = JSONObject(line)
            when (o.stringField("type", "")) {
                "session" -> {
                    // 会话 id 以文件首行 header 的 id 为准（spec §4.1）；version 未知静默按 1 处理
                    val headerId = o.stringField("id", "")
                    if (id == null && headerId.isNotBlank()) id = headerId
                }

                "message" -> {
                    val entryId = o.stringField("id", "")
                    if (entryId.isBlank()) return
                    val payload = o.optJSONObject("message") ?: return
                    // parentId 缺省 / JSON null → 根消息（字段可选容忍，spec §4.2）
                    val parentId = o.stringField("parentId", "").ifBlank { TREE_ROOT }
                    val m = StoredMessage(
                        id = entryId,
                        text = payload.stringField("text", ""),
                        fromUser = payload.boolField("fromUser", false),
                        isError = payload.boolField("isError", false),
                        reasoning = payload.stringField("reasoning", ""),
                        attachments = parseAttachments(payload),
                        toolCalls = parseToolCalls(payload),
                        toolCallId = payload.stringField("toolCallId", "").ifBlank { null },
                    )
                    val oldParent = parentOf[entryId]
                    if (oldParent == null) {
                        // 新节点：登记进 children[parentId] 末尾，并成为该父的默认活跃孩子
                        nodes[entryId] = m
                        childListOf(parentId).add(entryId)
                        parentOf[entryId] = parentId
                        activeChild[parentId] = entryId
                    } else {
                        // upsert：整行覆盖内容
                        nodes[entryId] = m
                        if (oldParent != parentId) {
                            // 改签：从旧父孩子表移除、挂新父末尾（spec §4.3.2）
                            children[oldParent]?.remove(entryId)
                            childListOf(parentId).add(entryId)
                            parentOf[entryId] = parentId
                            activeChild[parentId] = entryId
                        }
                    }
                }

                "session_info" -> {
                    title = o.stringField("name", "")
                    titleUserDefined = o.boolField("userDefined", false)
                    titleUserCount = o.optInt("userCount", 0)
                    // 活跃/置顶/归档元数据缺省（旧文件无字段）：0/false → 恢复端保持文件序稳定排序
                    lastActiveAt = o.optLong("lastActive", 0L)
                    pinned = o.boolField("pinned", false)
                    pinnedAt = o.optLong("pinnedAt", 0L)
                    archived = o.boolField("archived", false)
                }

                "active" -> {
                    val targetId = o.stringField("targetId", "")
                    val parentId = parentOf[targetId] ?: return  // 未知 target：按坏行容忍
                    activeChild[parentId] = targetId
                }

                "delete" -> {
                    val targetId = o.stringField("targetId", "")
                    val parentId = parentOf[targetId] ?: return  // 未知 target：按坏行容忍
                    val orphans = children.remove(targetId).orEmpty()
                    val siblings = childListOf(parentId)
                    val at = siblings.indexOf(targetId)
                    if (at >= 0) siblings.removeAt(at)
                    if (orphans.isEmpty()) {
                        // 叶子删除：活跃孩子切回最后剩余兄弟（对齐 AgentConversation.removeMessageAt）
                        if (activeChild[parentId] == targetId) {
                            if (siblings.isEmpty()) activeChild.remove(parentId)
                            else activeChild[parentId] = siblings.last()
                        }
                    } else {
                        // 过继：孤儿在父孩子列表中顶替被删节点位置，保持相对顺序
                        siblings.addAll(at.coerceAtLeast(0), orphans)
                        orphans.forEach { parentOf[it] = parentId }
                        // 活跃链重接：沿被删节点原本的活跃孩子继续（无则取最后一个孤儿）
                        val activeOrphan = activeChild.remove(targetId) ?: orphans.last()
                        if (activeChild[parentId] == targetId) {
                            activeChild[parentId] = activeOrphan
                        }
                    }
                    nodes.remove(targetId)
                    parentOf.remove(targetId)
                }

                // 未知 type：静默跳过
            }
        }

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            runCatching { fold(trimmed) }  // 残行 / 坏行静默跳过（spec §4.4）
        }
        val sessionId = id ?: return null  // 无 header
        // 空树不在此返回 null（fix round 1）：零节点会话照常产出，加载下游按 spec §6.2 处理
        return Parsed(sessionId, title, titleUserDefined, titleUserCount, lastActiveAt, pinned, pinnedAt, archived,
            TreeData(nodes, children, activeChild))
    }

    /** 工具调用数组；query 不落盘，这里从 arguments 重新提取（规则与 network 层一致） */
    private fun parseToolCalls(payload: JSONObject): List<ToolCall> {
        val arr = payload.optJSONArray("toolCalls") ?: return emptyList()
        val list = mutableListOf<ToolCall>()
        for (i in 0 until arr.length()) {
            runCatching {
                val c = arr.getJSONObject(i)
                val name = c.stringField("name", "")
                if (name.isBlank()) return@runCatching
                val args = c.stringField("arguments", "")
                list.add(
                    ToolCall(
                        id = c.stringField("id", ""),
                        name = name,
                        arguments = args,
                        query = if (name == "web_search") extractQuery(args) else null,
                    )
                )
            }
        }
        return list
    }

    /** 与 network 层 ToolCallAccumulator 同规则：畸形参数不抛，query 置空 */
    private fun extractQuery(arguments: String): String? {
        if (arguments.isBlank()) return null
        return runCatching {
            JSONObject(arguments).optString("query").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseAttachments(payload: JSONObject): List<Pair<String, String>> {
        val arr = payload.optJSONArray("attachments") ?: return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            runCatching {
                val a = arr.getJSONObject(i)
                list.add(a.stringField("uri", "") to a.stringField("name", ""))
            }
        }
        return list
    }

    /** 缺字段 / JSON null / 类型不符时取 fallback（不依赖各 org.json 实现的 optString 差异） */
    private fun JSONObject.stringField(key: String, fallback: String): String {
        if (!has(key)) return fallback
        val v = opt(key)
        return if (v == null || v === JSONObject.NULL) fallback else v.toString()
    }

    private fun JSONObject.boolField(key: String, fallback: Boolean): Boolean {
        val v = opt(key) ?: return fallback
        return if (v is Boolean) v else fallback
    }
}
