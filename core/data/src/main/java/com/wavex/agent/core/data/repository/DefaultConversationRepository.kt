package com.wavex.agent.core.data.repository

import android.content.Context
import android.os.Build
import com.wavex.agent.core.data.GeneratedFileStore
import com.wavex.agent.core.data.attachment.IntakeAttachmentStore
import com.wavex.agent.core.data.LegacyMigrator
import com.wavex.agent.core.data.SessionCodec
import com.wavex.agent.core.data.SessionDiff
import com.wavex.agent.core.data.SessionDirResolver
import com.wavex.agent.core.data.SessionStore
import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.SessionStorageStatus
import com.wavex.agent.core.domain.model.TreeData
import com.wavex.agent.core.domain.port.ConversationRepository
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ConversationRepository 端口的数据层实现：逐会话 JSONL 持久化的唯一所有者
 * （spec §5/§6/§7）。逻辑自 WavexViewModel 的持久化路径整体平移（行为逐条等价）：
 *
 * - 单写者：[sessionMutex] 串行全部写路径（sync 的 append/delete、moveAllTo、导入写盘）；
 * - 基线：[baselines]（sessionId → 最近落盘状态）是 SessionDiff 的比对基线，
 *   写回恒用 diff 产出的 next；
 * - [loadAll]：旧 conversations.json 一次性迁移 → store.loadAll → 为全部解析出的会话
 *   登记基线/known-id（含随后被 VM 裁掉的空草稿，其文件由 sync 的
 *   「内存消失 → 删文件」规则删除）→ 生成文件孤儿清扫（引用集 = 全部已加载会话全树附件）；
 * - [sync]：gone-id 文件删除 + 基线清理；未改名的空草稿永不落盘（spec §6.2 规则 1）；
 *   流式占位抑制（suppressedMessageIds，spec §5.3）；
 * - [refreshStorage]：resolve → canonical 比对 → moveAllTo（单写者互斥）→ 状态；
 *   IO 异常原样上抛（包装与日志兜底留在调用方 VM）；
 * - 导入：[importSessions]（调用方已分配新 id）写文件 → 折叠回放基线 → known-id 登记；
 *   写失败 best-effort 删残文件、停止、返回已成功数，取消照常传播。
 */
class DefaultConversationRepository private constructor(
    /** 生产装配传入 appContext（refreshStorage 的目录解析探针）；JVM 测试装配传 null */
    private val context: Context?,
    private val storeLazy: Lazy<SessionStore>,
    private val generatedFileStore: GeneratedFileStore?,
    /** 用户附件快照目录（filesDir/attachments/）：入口快照文件的孤儿清扫；JVM 测试装配可传 null */
    private val intakeFileStore: IntakeAttachmentStore?,
    private val legacyJsonFile: File?,
    private val resolveDir: (Context?) -> File,
    private val now: () -> String,
) : ConversationRepository {

    /**
     * 生产装配：store 惰性创建（resolve 触磁盘 IO，构造发生在主线程）；
     * legacyJsonFile = File(filesDir, "conversations.json")（一次性迁移源）；
     * 行时间戳 = ISO-8601（含本地时区偏移），与旧 WavexViewModel.nowIso 一致。
     */
    constructor(
        context: Context,
        generatedFileStore: GeneratedFileStore,
        intakeFileStore: IntakeAttachmentStore? = null,
    ) : this(
        context = context,
        storeLazy = lazy { SessionStore(SessionDirResolver.resolve(context)) },
        generatedFileStore = generatedFileStore,
        intakeFileStore = intakeFileStore,
        legacyJsonFile = File(context.filesDir, "conversations.json"),
        resolveDir = { ctx -> SessionDirResolver.resolve(requireNotNull(ctx)) },
        now = { OffsetDateTime.now().toString() },
    )

    /**
     * JVM 测试装配（同模块 internal）：直接注入已建 store 与各纯 seam。
     * resolveDir 参数为 (Context?) -> File：JVM 无法构造 Context 实例，且 Kotlin 对
     * lambda 的非空参数生成入口判空（实测字节码 Intrinsics.checkNotNullParameter），
     * 桩以可空参数声明才能在 context=null 时被调用；生产路径恒传非 null。
     */
    internal constructor(
        store: SessionStore,
        generatedFileStore: GeneratedFileStore?,
        intakeFileStore: IntakeAttachmentStore? = null,
        legacyJsonFile: File?,
        resolveDir: (Context?) -> File,
        now: () -> String,
    ) : this(
        context = null,
        storeLazy = lazy { store },
        generatedFileStore = generatedFileStore,
        intakeFileStore = intakeFileStore,
        legacyJsonFile = legacyJsonFile,
        resolveDir = resolveDir,
        now = now,
    )

    private val store: SessionStore get() = storeLazy.value

    /** sessionId → 最近一次落盘状态：diff 的比对基线；写回恒用 diff 产出的 next（不自算） */
    private val baselines = HashMap<String, SessionDiff.Flushed>()

    /** 已知在盘的会话 id（loadAll 结果 + 落盘成功的新会话）：内存消失的 id 由 sync 删文件 */
    private val knownIds = HashSet<String>()

    /** 会话文件单写者锁：append / delete / moveAllTo 全部串行 */
    private val sessionMutex = Mutex()

    override suspend fun loadAll(): List<ConversationSnapshot> = withContext(Dispatchers.IO) {
        // 旧 conversations.json → 逐会话 JSONL 一次性迁移（spec §7.1）：必须在加载前完成
        legacyJsonFile?.let { jsonFile ->
            LegacyMigrator.migrateIfNeeded(jsonFile, store, System.currentTimeMillis()) { now() }
        }
        val parsed = store.loadAll()
        // 基线/known-id 对全部解析出的会话登记（含随后被 VM dropExtraEmptyConversations
        // 裁掉的空草稿：零节点、无附件，孤儿清扫不受影响；其文件由 sync 的
        // 「内存消失 → 删文件」规则删除，与旧 VM 行为一致）
        parsed.forEach { p ->
            baselines[p.id] = SessionDiff.flushedOf(p.title, p.titleUserDefined, p.titleUserCount,
                p.lastActiveAt, p.pinned, p.pinnedAt, p.archived, p.tree)
            knownIds.add(p.id)
        }
        // 孤儿清扫（在基线登记之后）：引用集 = 全部已加载会话全树（含所有分支）的消息附件。
        // 生成文件（filesDir/generated/）与用户附件快照（filesDir/attachments/）分目录对账，共用同一引用集
        val referenced = parsed.flatMap { p ->
            p.tree.children.values.flatten().mapNotNull { id -> p.tree.nodes[id] }
        }.flatMap { m -> m.attachments.map { it.first } }.toSet()
        generatedFileStore?.sweepOrphans(referenced)
        intakeFileStore?.sweepOrphans(referenced)
        parsed.map { it.toSnapshot() }
    }

    override suspend fun sync(
        snapshots: List<AgentConversationData>,
        suppressedMessageIds: Map<String, String>,
    ) {
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                val liveIds = snapshots.mapTo(HashSet()) { it.id }
                knownIds.removeAll { id ->
                    val gone = id !in liveIds
                    if (gone) {
                        store.deleteSession(id)  // 文件可能本就不在：返回 false 无妨
                        // 基线一并清掉 —— 只清 knownIds 会按 id 泄漏整棵已落盘会话树
                        baselines.remove(id)
                    }
                    gone
                }
                snapshots.forEach { data ->
                    val prev = baselines[data.id]
                    // spec §6.2 规则 1：未改名的空草稿永不落盘 —— 整会话跳过，
                    // 不写基线、不进 knownIds（无文件可对应）；置顶/归档的空草稿视为有意保留，照常落盘
                    if (prev == null && data.nodes.isEmpty() && !data.titleUserDefined &&
                        !data.pinned && !data.archived
                    ) return@forEach
                    val actions = SessionDiff.diff(prev, data, suppressedMessageIds[data.id]) { now() }
                    if (actions.lines.isNotEmpty()) store.appendLines(data.id, actions.lines) { now() }
                    baselines[data.id] = actions.next
                    knownIds.add(data.id)
                }
            }
        }
    }

    override suspend fun importSessions(snapshots: List<AgentConversationData>): Int {
        var imported = 0
        for (snap in snapshots) {
            val id = snap.id   // 调用方在进入本方法前已分配（UUID）
            // 编解码（org.json 序列化 / 折叠回放 / 树重建）离开主线程：整库备份时逐会话成本可观；
            // 产物在登记发布前无并发读者，离线构建安全。
            val (lines, asParsed) = withContext(Dispatchers.Default) {
                // initialLines 以 ConversationSnapshot 为参数类型：导入数据（AgentConversationData）
                // 结构同构（nodes/children/activeChild 即 TreeData 三件套），直接包回即可
                val snapshot = ConversationSnapshot(
                    id = snap.id,
                    title = snap.title,
                    titleUserDefined = snap.titleUserDefined,
                    titleUserCount = snap.titleUserCount,
                    lastActiveAt = snap.lastActiveAt,
                    pinned = snap.pinned,
                    pinnedAt = snap.pinnedAt,
                    archived = snap.archived,
                    tree = TreeData(snap.nodes, snap.children, snap.activeChild),
                )
                val lines = SessionCodec.initialLines(snapshot, idOverride = id) { now() }
                // 基线 = 文件折叠结果（Parsed 等价），下一拍 diff 恒零行
                SessionCodec.parse(lines.joinToString("\n"))?.let { lines to it }
            } ?: continue
            sessionMutex.withLock {
                try {
                    // initialLines 契约 = 首行 header；appendLines 文件缺失时自写 header，
                    // 故剥掉首行避免双 header（final review：此前每个导入文件都有两条 session 行）
                    withContext(Dispatchers.IO) { store.appendLines(id, lines.drop(1)) { now() } }
                } catch (e: Exception) {
                    // 写失败：锁内 best-effort 删掉可能已落的「header+残行」文件——sync 清扫只查
                    // knownIds，不删会在下次 loadAll 复活成幽灵残会话；基线未登记，状态无痕。
                    // 停止导入、返回已成功数——干净的部分导入优于崩溃。
                    // 取消照常传播（与轮询 catch 同款模式）：导入被取消不能按写失败处理
                    if (e is CancellationException) throw e
                    android.util.Log.w(TAG, "legacy import append failed; aborting import", e)
                    runCatching { store.deleteSession(id) }
                    return imported
                }
                baselines[id] = SessionDiff.flushedOf(
                    asParsed.title, asParsed.titleUserDefined, asParsed.titleUserCount,
                    asParsed.lastActiveAt, asParsed.pinned, asParsed.pinnedAt, asParsed.archived, asParsed.tree
                )
                knownIds.add(id)
            }
            imported++
        }
        return imported
    }

    override suspend fun refreshStorage(): SessionStorageStatus {
        val store = storeLazy.value
        val usingPublic = withContext(Dispatchers.IO) {
            val resolved = resolveDir(context)   // 生产=appContext；测试桩忽略 null
            val public = isSameDir(resolved, SessionDirResolver.publicSessionsDir())
            if (!isSameDir(resolved, store.dir)) {
                // moveAllTo 过单写者锁（与 sync 写路径互斥）
                sessionMutex.withLock { store.moveAllTo(resolved) }
            }
            public
        }
        // API 26 以下即使授权 WRITE 也进不了公共档位（SessionDirResolver 真值表）→ 无从申请
        val canRequest = !usingPublic && Build.VERSION.SDK_INT >= 26
        return SessionStorageStatus(usingPublic, store.dir.absolutePath, canRequest)
    }

    /** 同一目录判定：含路径别名（符号链接 / 大小写 / 相对绝对拼写）——按 canonical 路径比对 */
    private fun isSameDir(a: File, b: File): Boolean =
        a == b || runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(false)

    private companion object {
        const val TAG = "DefaultConversationRepository"
    }
}
