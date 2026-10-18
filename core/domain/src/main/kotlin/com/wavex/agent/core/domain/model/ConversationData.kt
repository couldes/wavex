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
    /**
     * 思考块的签名（Anthropic signature_delta）。
     *
     * 正文持久化早就有了（reasoning），但 Anthropic 的 thinking 块是带签名的：
     * 追问重建历史时只回正文、签名丢了，回填轮仍会被上游拒。空串 = 该模型不给签名。
     */
    val thinkingSignature: String = "",
    val attachments: List<Pair<String, String>> = emptyList(),  // (uri, name)
    /** 本轮模型请求的工具调用（旧数据缺字段 → 空列表） */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 工具结果消息指向的调用 id（非工具消息为 null） */
    val toolCallId: String? = null,
    /** 引用映射（落盘：重启后仍能把 [cite:id] 渲染成可点角标） */
    val citationRefs: List<CitationRef> = emptyList(),
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
    /** 置顶标记与置顶时刻（epoch ms）：置顶区按 pinnedAt 降序，位置固定不随活跃跳动 */
    val pinned: Boolean = false,
    val pinnedAt: Long = 0,
    /** 归档标记：移出主列表、可搜索命中；与置顶互斥 */
    val archived: Boolean = false,
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
    val pinned: Boolean = false,
    val pinnedAt: Long = 0,
    val archived: Boolean = false,
    val tree: TreeData
)
