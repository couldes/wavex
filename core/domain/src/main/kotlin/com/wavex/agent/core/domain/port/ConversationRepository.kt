package com.wavex.agent.core.domain.port

import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.SessionStorageStatus

/**
 * 会话持久化端口（spec §5/§6/§7）：逐会话 JSONL 落盘的唯一入口。
 * 实现由 data 层提供（DefaultConversationRepository）；UI/VM 只依赖本端口 ——
 * SessionStore/SessionCodec/SessionDiff 等文件壳类型一律收进 data 模块内部。
 */
interface ConversationRepository {

    /** 启动加载：旧 conversations.json 一次性迁移 → 逐会话加载（文件名降序）。 */
    suspend fun loadAll(): List<ConversationSnapshot>

    /**
     * 把当前全部活跃会话的纯数据形态同步到磁盘：gone-id 文件删除 + 空/抑制跳过 +
     * diff 追加 + 基线写回。suppressedMessageIds: 会话 id → 流式占位消息 id
     * （登记期间该 id 的增/改/删与指向它的 active 行全部跳过）。
     */
    suspend fun sync(
        snapshots: List<AgentConversationData>,
        suppressedMessageIds: Map<String, String> = emptyMap(),
    )

    /**
     * 以【调用方已分配的新 id】把快照导入为新会话文件（initialLines → appendLines →
     * 基线/known-id 登记）。写失败：best-effort 删除残文件后停止，返回已成功数；
     * CancellationException 照常传播。
     */
    suspend fun importSessions(snapshots: List<AgentConversationData>): Int


    /**
     * 重解析会话落盘目录（存储授权升档后）：目录变化 → 全部会话文件搬移过去（单写者互斥），
     * 返回当前状态；resolve/moveAllTo 的 IO 异常原样上抛，由调用方决定如何兜底。
     */
    suspend fun refreshStorage(): SessionStorageStatus
}
