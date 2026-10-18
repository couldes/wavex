package com.wavex.agent.core.data

import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.model.TREE_ROOT
import com.wavex.agent.core.domain.model.TreeData

/**
 * diff 追加规划器（spec §5.2/§5.3）：比对当前会话状态与上一落盘状态（[Flushed]），
 * 产出要追加到会话 .jsonl 尾部的行序列。纯函数 —— 不碰文件，由调用方（单写者）
 * 把 [Actions.lines] 交给 SessionStore.appendLines，写盘成功后把 [Actions.next] 写回 lastFlushed。
 *
 * 行序：session_info → message（新/改，按 children 桶序）→ delete → active。
 * active 行必须在 message 行之后：parse 折叠时 active 需要 target 已入树（parentOf 已知）。
 * delete 行无需为「被删节点的孤儿改签」补任何行 —— parse 的 delete 折叠与
 * AgentConversation.removeMessageById 同构（孤儿顶替原位置、活跃链取原活跃孩子）。
 *
 * suppress：流式占位消息 id（spec §5.3）——该 id 的增（新 message 行）/改（upsert）/删
 * （delete）与指向它的 active 行全部跳过，占位内容增长期间文件里不留任何痕迹；
 * [Actions.next] 恒为当前状态，收尾（finally 清除 suppress）后凭内容指纹变化一次性补落。
 */
internal object SessionDiff {

    /** 上一落盘状态；nodes: msgId -> 内容指纹(hash) */
    data class Flushed(
        val title: String,
        val titleUserDefined: Boolean,
        val titleUserCount: Int,
        val lastActiveAt: Long = 0,
        val pinned: Boolean = false,
        val pinnedAt: Long = 0,
        val archived: Boolean = false,
        val nodes: Map<String, Long>,
        val activeChild: Map<String, String>
    )

    fun flushedOf(
        title: String,
        userDefined: Boolean,
        userCount: Int,
        lastActiveAt: Long = 0,
        pinned: Boolean = false,
        pinnedAt: Long = 0,
        archived: Boolean = false,
        tree: TreeData
    ): Flushed = Flushed(
        title = title,
        titleUserDefined = userDefined,
        titleUserCount = userCount,
        lastActiveAt = lastActiveAt,
        pinned = pinned,
        pinnedAt = pinnedAt,
        archived = archived,
        nodes = tree.nodes.mapValues { (_, m) -> fingerprint(m) },
        activeChild = tree.activeChild.toMap(),
    )

    data class Actions(val lines: List<String>, val next: Flushed)

    /**
     * prev=null 表示该会话尚未落盘（调用方 appendLines 会自动建 header）；
     * 此时标题三元组的折叠基线为空（"" / false / 0），activeChild 基线为空表（全量成 active 行，
     * 矫正 parse「文件序最后一个孩子为默认活跃」的折叠默认）。
     * suppress：流式占位 id（见对象 KDoc）；now：行时间戳供应商（ISO-8601 字符串）。
     */
    fun diff(
        prev: Flushed?,
        conv: AgentConversationData,
        suppress: String?,
        now: () -> String
    ): Actions {
        val lines = mutableListOf<String>()

        // ---- session_info：标题三元组或活跃/置顶/归档元数据变化（prev=null 时与空文件折叠基线比对）----
        val prevTitle = prev?.title ?: ""
        val prevUserDefined = prev?.titleUserDefined ?: false
        val prevUserCount = prev?.titleUserCount ?: 0
        val prevLastActive = prev?.lastActiveAt ?: 0L
        val prevPinned = prev?.pinned ?: false
        val prevPinnedAt = prev?.pinnedAt ?: 0L
        val prevArchived = prev?.archived ?: false
        if (conv.title != prevTitle || conv.titleUserDefined != prevUserDefined ||
            conv.titleUserCount != prevUserCount || conv.lastActiveAt != prevLastActive ||
            conv.pinned != prevPinned || conv.pinnedAt != prevPinnedAt || conv.archived != prevArchived
        ) {
            lines += SessionCodec.buildSessionInfo(now(), conv.title, conv.titleUserDefined, conv.titleUserCount,
                conv.lastActiveAt, conv.pinned, conv.pinnedAt, conv.archived)
        }

        val prevNodes = prev?.nodes ?: emptyMap()
        val prevActive = prev?.activeChild ?: emptyMap()

        // parentId 反查表（同 id 出现在多个桶的脏数据：首桶为准）
        val parentOf = HashMap<String, String>()
        conv.children.forEach { (parent, ids) -> ids.forEach { id -> parentOf.putIfAbsent(id, parent) } }

        // ---- message 行：新节点 + 内容指纹变化的已知节点（按 children 桶序遍历，顺序确定）----
        val emitted = HashSet<String>()
        // parent -> 本 diff 最后挂上的新孩子：parse 折叠的新节点会成为该父的默认活跃孩子，
        // 与真实 activeChild 不一致时（流式中用户切走分支后收尾）需在尾部补 active 行矫正
        val foldDefault = HashMap<String, String>()
        conv.children.forEach { (_, ids) ->
            ids.forEach { id ->
                if (!emitted.add(id)) return@forEach      // 脏数据同 id 多桶：只落首桶
                val m = conv.nodes[id] ?: return@forEach  // 桶里有 id 但无节点：跳过
                if (id == suppress) return@forEach        // 流式占位：增/改全跳过（spec §5.3）
                val parent = parentOf[id] ?: return@forEach  // 未父化（不在任何桶）：无 parentId 可写
                val hash = fingerprint(m)
                val old = prevNodes[id]
                // 根消息：文件格式用 JSON null（parse 端映射回 TREE_ROOT），非字面量 "root"
                val parentArg = parent.takeUnless { it == TREE_ROOT }
                if (old == null) {
                    foldDefault[parent] = id
                    lines += SessionCodec.buildMessageEntry(id, parentArg, now(), m)
                } else if (old != hash) {
                    lines += SessionCodec.buildMessageEntry(id, parentArg, now(), m)  // upsert
                }
            }
        }

        // ---- delete 行：prev 有、当前无、非抑制 ----
        prevNodes.keys.forEach { id ->
            if (id != suppress && !conv.nodes.containsKey(id)) {
                lines += SessionCodec.buildDelete(now(), id)
            }
        }

        // ---- active 行：activeChild[p] 变化（在 message 行之后，保证 fold 时 target 已知）----
        val activeEmitted = HashSet<String>()
        conv.activeChild.forEach { (parent, child) ->
            if (child != suppress && prevActive[parent] != child) {
                activeEmitted += parent
                lines += SessionCodec.buildActive(now(), child)
            }
        }
        // fold 默认活跃孩子矫正：本 diff 给某父新挂了孩子 → fold 默认 = 最后挂上的那个；
        // 真实 activeChild 与之不同且该父还没发过 active 行时补一行（正常追加路径两者恒相等，零额外行）
        foldDefault.forEach { (parent, lastNew) ->
            val desired = conv.activeChild[parent]
            if (desired != null && desired != suppress && parent !in activeEmitted && desired != lastNew) {
                lines += SessionCodec.buildActive(now(), desired)
            }
        }

        val flushed = flushedOf(conv.title, conv.titleUserDefined, conv.titleUserCount, conv.lastActiveAt,
            conv.pinned, conv.pinnedAt, conv.archived, TreeData(conv.nodes, conv.children, conv.activeChild))
        // 被抑制的占位不进落盘簿记：它未持久化，收尾（suppress 清除）后仍须被视作「新节点」，
        // 否则末次流式 tick 已哈希过最终内容时 diff 看不到变化，最终回复将永不落盘（spec §5.3）
        val next = if (suppress == null) flushed else flushed.copy(nodes = flushed.nodes - suppress)
        return Actions(lines, next)
    }

    /** 内容指纹（spec §5.2）：碰撞概率在单会话编辑粒度可忽略 */
    private fun fingerprint(m: StoredMessage): Long =
        listOf(
            m.text, m.fromUser, m.isError, m.reasoning, m.thinkingSignature, m.attachments,
            // 工具调用必须进指纹：漏了会让「只有工具调用变化」的消息不落盘（重启即丢）
            m.toolCalls, m.toolCallId, m.citationRefs,
            // 模型名同理：收尾时只回填模型名的行必须被识别为变更
            m.model,
            // 断流收尾只翻 interrupted、正文不再变：不进指纹就永远落不了盘
            m.interrupted,
        ).hashCode().toLong()
}
