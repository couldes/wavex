package com.wavex.agent.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/** 分叉树持久化的根键：消息 id 都是 UUID，用 "root" 不会冲突 */
internal const val TREE_ROOT = "root"

/**
 * 对话 = 消息分叉树（ChatGPT 式）：
 * - children: 父消息 id -> 子消息列表（同一位置的所有版本），根挂在 TREE_ROOT 下；
 * - activeChild: 父消息 id -> 当前激活的子消息 id（每个节点记住自己走的是哪个孩子）；
 * - messages: 当前活跃路径，渲染与请求都只看它；切换版本时按 activeChild 重建。
 * 编辑重发不再销毁旧内容，而是新增兄弟版本，可随时 ‹ › 切回。
 */
internal class AgentConversation(
    val id: String,
    initialTitle: String
) {
    var title by mutableStateOf(initialTitle)

    val children = androidx.compose.runtime.mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    val activeChild = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** 当前活跃路径（渲染 / 发送历史都用它） */
    val messages = mutableStateListOf<ChatMessage>()

    fun childrenOf(parentId: String): SnapshotStateList<ChatMessage> =
        children.getOrPut(parentId) { mutableStateListOf() }

    private fun parentIdAt(index: Int): String =
        if (index <= 0) TREE_ROOT else messages[index - 1].id

    /** 追加到路径末尾（同时登记进树）。所有消息入列必须走这里，保证树一致。 */
    fun appendMessage(msg: ChatMessage) {
        val parentId = messages.lastOrNull()?.id ?: TREE_ROOT
        childrenOf(parentId).add(msg)
        activeChild[parentId] = msg.id
        messages.add(msg)
    }

    /** 就地更新路径上 index 处的消息（树里同步替换，id 不变）；越界安全（取消竞态时占位可能已移除） */
    fun updateMessageAt(index: Int, msg: ChatMessage) {
        if (index < 0 || index >= messages.size) return
        messages[index] = msg
        children[parentIdAt(index)]?.let { siblings ->
            val i = siblings.indexOfFirst { it.id == msg.id }
            if (i >= 0) siblings[i] = msg
        }
    }

    /** 在 index 处插入新版本（编辑重发）：旧版本及其后续分支全部保留在树上 */
    fun insertVersionAt(index: Int, msg: ChatMessage) {
        val parentId = parentIdAt(index)
        childrenOf(parentId).add(msg)
        activeChild[parentId] = msg.id
        rebuildFrom(index)
    }

    /** 切换到 index 处的另一个版本：旧分支保留，路径按各节点记忆的活跃子消息重建 */
    fun switchVersionAt(index: Int, sibling: ChatMessage) {
        activeChild[parentIdAt(index)] = sibling.id
        rebuildFrom(index)
    }

    /** 从 index 起按 activeChild 重建路径（index 之前保持不动） */
    private fun rebuildFrom(index: Int) {
        while (messages.size > index) messages.removeAt(messages.size - 1)
        var parentId = if (index == 0) TREE_ROOT else messages[index - 1].id
        while (true) {
            val nextId = activeChild[parentId] ?: break
            val node = children[parentId]?.firstOrNull { it.id == nextId } ?: break
            messages.add(node)
            parentId = node.id
        }
    }

    /** 加载持久化数据后重建整条路径 */
    fun loadTree(
        nodes: Map<String, ChatMessage>,
        children: Map<String, List<String>>,
        activeChild: Map<String, String>
    ) {
        children.forEach { (parent, ids) ->
            val list = childrenOf(parent)
            ids.forEach { id -> nodes[id]?.let { list.add(it) } }
        }
        this.activeChild.putAll(activeChild)
        messages.clear() // O(1)：之前 removeAt(0) 循环是 O(n²)
        rebuildFrom(0)
    }

    /** 从路径和树上移除 index 处的消息（取消生成时的空占位），并把活跃子切回兄弟版本 */
    fun removeMessageAt(index: Int) {
        val parentId = parentIdAt(index)
        val msg = messages.getOrNull(index) ?: return
        messages.removeAt(index)
        children[parentId]?.removeAll { it.id == msg.id }
        if (activeChild[parentId] == msg.id) {
            val rest = children[parentId]
            if (rest.isNullOrEmpty()) activeChild.remove(parentId)
            else activeChild[parentId] = rest.last().id
        }
        rebuildFrom(index)
    }

    /** index 处的所有兄弟版本（含当前），>1 时显示 ‹ k/n › 切换器 */
    fun siblingsOf(index: Int): List<ChatMessage> = children[parentIdAt(index)] ?: emptyList()

    /** 内容指纹：用于持久化去抖（标题+消息数+末条长度+树规模），避免流式期间每帧全量比较 */
    fun fingerprint(): String {
        val last = messages.lastOrNull()
        val treeSize = children.values.sumOf { it.size }
        return "$title|${messages.size}|${last?.text?.length ?: 0}|${last?.reasoning?.length ?: 0}|${last?.attachments?.size ?: 0}|$treeSize"
    }
}

/** 纯数据形态（与 UI 状态解耦） */
data class StoredMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false,
    val reasoning: String = "",  // 模型思考过程（可空，旧数据自动补 ""）
    val attachments: List<Pair<String, String>> = emptyList()  // (uri, name)
)

/** 分叉树纯数据：nodes=全部节点，children=父 id -> 子 id 列表，activeChild=父 id -> 激活子 id */
data class TreeData(
    val nodes: Map<String, StoredMessage>,
    val children: Map<String, List<String>>,
    val activeChild: Map<String, String>
)

data class AgentConversationData(
    val id: String,
    val title: String,
    val nodes: Map<String, StoredMessage>,
    val children: Map<String, List<String>>,
    val activeChild: Map<String, String>
)

/** 加载用快照 */
data class ConversationSnapshot(val id: String, val title: String, val tree: TreeData)
