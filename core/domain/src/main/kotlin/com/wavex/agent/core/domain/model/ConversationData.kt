package com.wavex.agent.core.domain.model

/** 分叉树持久化的根键：消息 id 都是 UUID，用 "root" 不会冲突 */
const val TREE_ROOT = "root"

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
    val titleUserDefined: Boolean = false,
    val titleUserCount: Int = 0,
    /** 最后活跃时间（epoch ms）：0 = 未知（旧数据），恢复时按文件序稳定排序 */
    val lastActiveAt: Long = 0,
    val nodes: Map<String, StoredMessage>,
    val children: Map<String, List<String>>,
    val activeChild: Map<String, String>
)

/** 加载用快照 */
data class ConversationSnapshot(
    val id: String,
    val title: String,
    val titleUserDefined: Boolean = false,
    val titleUserCount: Int = 0,
    val lastActiveAt: Long = 0,
    val tree: TreeData
)
