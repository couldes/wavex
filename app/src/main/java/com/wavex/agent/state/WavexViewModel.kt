package com.wavex.agent.state

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wavex.agent.model.AgentConversation
import com.wavex.agent.ui.MainTab
import com.wavex.agent.ui.ThemeChoice
import com.wavex.agent.data.ConversationStore
import com.wavex.agent.data.ProviderStore
import com.wavex.agent.data.SafBackupStore
import com.wavex.agent.engine.HistoryBuilder
import com.wavex.agent.engine.FallbackAction
import com.wavex.agent.engine.FallbackFlags
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.model.StoredMessage
import com.wavex.agent.model.TitlePolicy
import com.wavex.agent.model.AgentConversationData
import com.wavex.agent.model.ConversationSnapshot
import com.wavex.agent.model.ChatRequestMessage
import com.wavex.agent.data.Provider
import com.wavex.agent.data.resolveToMillis
import com.wavex.agent.ui.shared.attachmentDisplayName
import com.wavex.agent.network.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * 全局应用状态（原 AgentState 整体平移）：草稿、滚动位置、模型页状态、生成引擎。
 * 持有 ViewModel 生命周期：旋转/深色切换等配置变更不再销毁重建，流式输出不中断。
 */
internal class WavexViewModel(
    private val providerStore: ProviderStore,
    private val conversationStore: ConversationStore?,
    /** SAF 备份文件夹（防卸载的唯一可靠层）；未选文件夹时也能构造，操作全部空安全 */
    private val safBackup: SafBackupStore? = null,
    /** 用量统计存储；未注入时统计页显示空态（测试构造不依赖 Android Context） */
    private val usageStore: com.wavex.agent.data.UsageStore? = null
) : ViewModel() {
    var themeChoice by mutableStateOf(ThemeChoice.SYSTEM)
    var selectedTab by mutableStateOf(MainTab.CHAT)

    // ---------- 用量统计（底部 tab） ----------
    var usageRange by mutableStateOf<com.wavex.agent.data.UsageRange>(com.wavex.agent.data.UsageRange.Today)
    var usageProviderFilter by mutableStateOf<String?>(null)   // null = 全部
    var usageData by mutableStateOf(UsagePageData.EMPTY)
        private set

    /** 重算统计页全部数据（进页面 / 范围或筛选变化 / 有新记录落账时调用）。
     *  聚合查询含文件读取 + JSON 解析（日志保留 90 天，量大），必须放 IO 线程：
     *  旧实现在主线程同步做 5 次全量日志解析，切筛选/进页面时明显掉帧。
     *  快速切换筛选时取消上一个请求，只应用最新一次的结果。 */
    private var usageRefreshJob: kotlinx.coroutines.Job? = null

    fun refreshUsageData() {
        val store = usageStore ?: run { usageData = UsagePageData.EMPTY; return }
        usageRefreshJob?.cancel()
        val range = usageRange
        val filter = usageProviderFilter
        usageRefreshJob = engineScope.launch {
            val zone = java.time.ZoneId.systemDefault()
            val (s, e) = range.resolveToMillis(java.time.LocalDate.now(zone), zone)
            val data = withContext(Dispatchers.IO) {
                UsagePageData(
                    summary = store.summary(s, e, filter),
                    providerStats = store.providerStats(s, e),
                    modelStats = store.modelStats(s, e, filter),
                    trends = store.trends(s, e, filter),
                    recentLogs = store.recentLogs(s, e, filter)
                )
            }
            if (isActive) usageData = data
        }
    }

    fun changeUsageRange(r: com.wavex.agent.data.UsageRange) {
        usageRange = r
        refreshUsageData()
    }

    fun changeUsageProviderFilter(v: String?) {
        usageProviderFilter = v
        refreshUsageData()
    }

    // 思考等级（持久化）与联网搜索开关（会话级）
    var reasoningEffort by mutableStateOf("")
    var webSearch by mutableStateOf(false)

    // 生成引擎提升到全局状态：切 Tab/主题时对话页可安全离开组合树，
    // 流式协程不依附于某个页面的生命周期（修主题切换卡顿：不再全树重组）
    // viewModelScope 默认即 Dispatchers.Main.immediate + SupervisorJob，语义与原 engineScope 一致
    private val engineScope get() = viewModelScope

    // 每个对话独立的输入草稿 / 附件草稿 / 滚动位置
    val inputDrafts = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val attachmentDrafts = androidx.compose.runtime.mutableStateMapOf<String, SnapshotStateList<ChatAttachment>>()
    val listStates = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
    // 外部写入草稿（系统分享文本）时自增，通知 ChatScreen 重新读草稿到输入框
    val draftVersions = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    fun draftInputFor(conversationId: String): String = inputDrafts[conversationId] ?: ""
    fun setDraftInput(conversationId: String, value: String) {
        if (value.isBlank()) inputDrafts.remove(conversationId) else inputDrafts[conversationId] = value
    }
    fun attachmentsFor(conversationId: String): SnapshotStateList<ChatAttachment> =
        attachmentDrafts.getOrPut(conversationId) { mutableStateListOf() }
    fun listStateFor(conversationId: String): androidx.compose.foundation.lazy.LazyListState =
        listStates.getOrPut(conversationId) { androidx.compose.foundation.lazy.LazyListState() }

    // 模型页状态也提升：切 Tab 不重新拉取 /models，不丢搜索词
    var modelsFetched by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var modelsLoading by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var modelsSearchQuery by mutableStateOf("")
    var modelsShowManual by mutableStateOf(false)
    var modelsManualModel by mutableStateOf("")

    /** 模型页拉取成功后登记：内存供 UI 立即使用，同时持久化，
     *  下次冷启动模型页首帧直接显示这份列表（见 init）。失败时调用方不调本方法，保留原列表。 */
    fun recordFetchedModels(providerId: String, models: List<String>) {
        modelsFetched = modelsFetched + (providerId to models)
        providerStore.saveFetchedModels(modelsFetched)
    }

    // 按会话跟踪生成状态：多个对话可同时流式（A 生成时切到 B，B 照常能发）。
    // activeGenerations 是 Compose 可观察集合，不能再用单个 generatingIn 覆盖不同会话。
    private val activeGenerations = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val streamJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun isGeneratingIn(conversationId: String): Boolean = activeGenerations.containsKey(conversationId)

    fun stopGeneration(conversationId: String) {
        // 仅停止指定会话（唯一调用点就是当前会话的停止钮）：
        // 不再做“任取第一个运行中任务”的兕底——Map 迭代顺序不确定，
        // 多会话并发时会停错对话
        val job = synchronized(streamJobs) { streamJobs.remove(conversationId) }
        activeGenerations.remove(conversationId)
        job?.cancel()
    }

    /**
     * 生成引擎：构建历史（每条用户消息独立加载附件）→ SSE 流式 → 帧驱动打字机。
     * 运行在 engineScope，与页面组合无关；每个对话独立一条协程，互不阻塞。
     */
    fun startGeneration(context: android.content.Context, conversation: AgentConversation) {
        // 同一对话不叠加生成；不同对话各自开一条流（并发）
        if (activeGenerations.containsKey(conversation.id)) return
        val provider = currentProvider ?: return
        val messages = conversation.messages
        val convId = conversation.id
        // 先登记再启动协程，防止快速双击/切回同一会话时追加第二条用户消息。
        activeGenerations[convId] = true
        val job = engineScope.launch {
            val assistantIndex = messages.size
            // 占位气泡的索引：若插入了「不支持的附件」提示，占位会被推后一位
            var placeholderIndex = assistantIndex
            try {
                // 历史构建外移到 engine/HistoryBuilder：每条用户消息独立加载附件
                // （图片→视觉消息、音频→input_audio、文本→内联），多轮发图不丢图
                val built = com.wavex.agent.engine.HistoryBuilder.build(
                    messages.take(assistantIndex),
                    com.wavex.agent.engine.HistoryBuilder.systemLoader(context)
                )
                val history = built.history
                built.unreadableName?.let {
                    // 软件能收的格式挑选时不拦（见 loadContent），能到这里只剩文件失效
                    conversation.appendMessage(ChatMessage(text = "无法读取「$it」的内容，文件可能已被移动或删除", fromUser = false, isError = true))
                }
                conversation.appendMessage(ChatMessage(text = "", fromUser = false))
                placeholderIndex = messages.size - 1

                val target = StringBuilder()
                var shown = 0
                // 思考过程单独缓冲：qwen3/gpt-6 等会先流式吐 reasoning_content 再吐正文，
                // 存进 ChatMessage.reasoning，由气泡渲染成可折叠区块（正文到达后自动收起）
                val reasoningBuf = StringBuilder()
                var reasoningShown = 0
                val typewriter = launch {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        // 自适应节奏：中转站常把大段内容塞进少数 chunk（几十上百字/chunk），
                        // 固定 28%/帧永远追不上 → 观感「卡一下全屏出」。
                        // 落后量大时降帧率（16→32→48→64ms）、步长加大（28%→45%），
                        // 少量落后时保持原来的 16ms/28% 手感。
                        val lag = (target.length - shown) + (reasoningBuf.length - reasoningShown)
                        kotlinx.coroutines.delay(
                            when {
                                lag > 800 -> 64L
                                lag > 300 -> 48L
                                lag > 120 -> 32L
                                else -> 16L
                            }
                        )
                        val full = target.length
                        val rFull = reasoningBuf.length
                        if (shown < full || reasoningShown < rFull) {
                            // 步长随落后量自适应（至少 1 字）：温和渐进 vs 快速追赶
                            val big = lag > 300
                            val step = maxOf(1, ((full - shown) * (if (big) 0.45f else 0.28f)).toInt())
                            shown = minOf(full, shown + step)
                            val rStep = maxOf(1, ((rFull - reasoningShown) * (if (big) 0.45f else 0.28f)).toInt())
                            reasoningShown = minOf(rFull, reasoningShown + rStep)
                            conversation.updateMessageAt(
                                placeholderIndex,
                                messages[placeholderIndex].copy(
                                    text = target.substring(0, shown),
                                    reasoning = reasoningBuf.substring(0, reasoningShown)
                                )
                            )
                        }
                    }
                }
                val collectDeltas: (String, String) -> Unit = { delta, reasoningDelta ->
                    if (reasoningDelta.isNotEmpty()) reasoningBuf.append(reasoningDelta)
                    if (delta.isNotEmpty()) target.append(delta)
                }
                // 每次都带参数发送：被拒时按参数逐个降级重试并各自 Toast 提示。
                // （曾做过“记住被拒参数”的缓存，但中转站报错文案千奇百怪难维护，已弃用）
                val effortParam: String? = reasoningEffort.ifBlank { null }
                val webParam: Boolean = webSearch
                // 递归降级：音频 → 图片 → 思考等级 → 联网搜索，逐层剔除被模型拒绝的部分。
                // 错误分类：isModelError(msg) = 模型不收这类内容（格式本身没问题，软件能处理）；
                // 其余 HTTP 4xx = 参数不支持。每次降级 Toast 明确告知用户原因与处理方式
                suspend fun send(h: List<ChatRequestMessage>, effort: String?, web: Boolean, audioDropped: Boolean, imageDropped: Boolean, pdfDropped: Boolean = false) {
                    try {
                        ApiClient.streamChat(provider, h, reasoningEffort = effort, webSearch = web, onDelta = collectDeltas)
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        // 降级决策外移到 engine/FallbackPolicy（关键词逐字保留）；重发与 Toast 留在这里
                        val flags = com.wavex.agent.engine.FallbackFlags(
                            historyHasAudio = built.historyHasAudio,
                            historyHasImage = built.historyHasImage,
                            historyHasPdf = built.historyHasPdf,
                            audioDropped = audioDropped,
                            imageDropped = imageDropped,
                            pdfDropped = pdfDropped,
                            effort = effort,
                            web = web
                        )
                        when (com.wavex.agent.engine.FallbackPolicy.decide(msg, flags)) {
                            com.wavex.agent.engine.FallbackAction.DropAudio -> {
                                Toast.makeText(context, "当前模型不支持音频输入，已忽略音频继续回答", Toast.LENGTH_LONG).show()
                                send(built.withFallbackNote(built.noAudioHistory), effort, web, audioDropped = true, imageDropped)
                            }
                            com.wavex.agent.engine.FallbackAction.DropImage -> {
                                Toast.makeText(context, "当前模型不支持图片输入，已忽略图片继续回答（如需看图请换支持视觉的模型）", Toast.LENGTH_LONG).show()
                                send(built.withFallbackNote(built.noImageHistory), effort, web, audioDropped, imageDropped = true)
                            }
                            com.wavex.agent.engine.FallbackAction.DropPdf -> {
                                Toast.makeText(context, "当前模型不支持 PDF 输入，请改用图片发送或换支持的模型", Toast.LENGTH_LONG).show()
                                send(built.withFallbackNote(built.noPdfHistory), effort, web, audioDropped, imageDropped, pdfDropped = true)
                            }
                            com.wavex.agent.engine.FallbackAction.DropEffort -> {
                                Toast.makeText(context, "当前模型不支持所选思考等级，已忽略该设置继续回答", Toast.LENGTH_SHORT).show()
                                send(h, null, web, audioDropped, imageDropped)
                            }
                            com.wavex.agent.engine.FallbackAction.DropWeb -> {
                                Toast.makeText(context, "当前模型不支持联网搜索，已忽略该设置继续回答", Toast.LENGTH_SHORT).show()
                                // 保留 effort：联网被拒不代表思考等级被拒，之前误传 null 把思考等级也静默丢掉
                                send(h, effort, false, audioDropped, imageDropped)
                            }
                            com.wavex.agent.engine.FallbackAction.None -> throw e
                        }
                    }
                }
                try {
                    send(history, effortParam, webParam, audioDropped = false, imageDropped = false)
                    // 流正常结束：先等打字机把残余内容追平再取消，避免「结尾几百字
                    // 一帧涌出」的跳动；带上最后一段延迟的余量，多数情况一轮即满足
                    while (shown < target.length || reasoningShown < reasoningBuf.length) {
                        kotlinx.coroutines.delay(16)
                    }
                } finally {
                    typewriter.cancel()
                }
                // 收尾：确保全部显示（正常路径此处与打字机已写内容一致，无视觉变化；
                // 仅兕底异常时序；取消竞态时占位可能已被移除，需判空）
                messages.getOrNull(placeholderIndex)?.let {
                    conversation.updateMessageAt(placeholderIndex, it.copy(text = target.toString(), reasoning = reasoningBuf.toString()))
                }
                if (messages.getOrNull(placeholderIndex)?.text.isNullOrBlank() &&
                    messages.getOrNull(placeholderIndex)?.reasoning.isNullOrBlank() &&
                    placeholderIndex < messages.size
                ) {
                    // 必须用 copy() 保留原 id：updateMessageAt 按 id 同步树节点，
                    // 换成新建 ChatMessage（新 id）时路径上显示报错文案、树里仍是空占位，
                    // 切分支/重启后由树重建路径 → 变回空气泡（实测：联网空流后切分支）
                    messages.getOrNull(placeholderIndex)?.let {
                        conversation.updateMessageAt(
                            placeholderIndex,
                            it.copy(text = "（模型没有返回内容）", isError = true)
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点了停止：保留已生成的部分；完全没内容则移除占位并切回旧分支
                if (messages.getOrNull(placeholderIndex)?.text.isNullOrBlank() && placeholderIndex < messages.size) {
                    conversation.removeMessageAt(placeholderIndex)
                }
            } catch (e: Exception) {
                val msg = e.message?.take(300) ?: "网络错误"
                messages.getOrNull(placeholderIndex)?.let {
                    conversation.updateMessageAt(placeholderIndex, it.copy(
                        text = "请求失败：$msg",
                        isError = true
                    ))
                }
            } finally {
                streamJobs.remove(convId)
                activeGenerations.remove(convId)
                // 每轮生成结束后检查标题：首题或里程碑演化（ChatGPT 式及时反映对话内容）
                maybeUpdateTitle(conversation)
            }
        }
        streamJobs[convId] = job
    }

    /** per-conversation 标题生成锁：上一轮标题请求未返回前不叠加新请求 */
    private val titleInFlight = mutableSetOf<String>()

    /**
     * 标题生成/演化统一入口（每轮生成结束后调用）。
     * - 首题：标题还是「新对话」占位时，用首轮问答生成；失败回退首条消息截断（与旧行为一致）；
     * - 演化：每新增 TitlePolicy.EVOLVE_EVERY 条用户消息，用最近几轮重新生成；失败静默保留旧题；
     * - 手动改名后永不自动覆盖（TitlePolicy 硬闸门）。
     */
    private fun maybeUpdateTitle(conversation: AgentConversation) {
        if (conversation.id in titleInFlight) return
        val transcript = TitlePolicy.transcript(
            conversation.titleIsUserDefined, conversation.title,
            conversation.messages, conversation.titleUserCount
        ) ?: return
        if (conversation.title == "新对话") {
            val firstUser = conversation.messages.firstOrNull { it.fromUser && !it.isError } ?: return
            if (firstUser.text.isBlank() && firstUser.attachments.isEmpty()) return
        }
        val provider = currentProvider ?: return
        titleInFlight.add(conversation.id)
        engineScope.launch {
            try {
                val generated = ApiClient.generateTitle(provider, transcript)
                if (generated != null) {
                    conversation.title = generated
                } else if (conversation.title == "新对话") {
                    // 首次生成失败：回退首条消息截断；演化失败不动旧题
                    val firstUser = conversation.messages.firstOrNull { it.fromUser && !it.isError }
                    conversation.title = firstUser?.text?.take(28)?.ifBlank {
                        firstUser.attachments.firstOrNull()?.let { attachmentDisplayName(it.name).take(20) } ?: "新对话"
                    } ?: "新对话"
                } else return@launch   // 演化失败：基线不推进，下个里程碑自然重试
                conversation.titleUserCount = conversation.messages.count { it.fromUser && !it.isError }
                schedulePersist()
            } finally {
                titleInFlight.remove(conversation.id)
            }
        }
    }

    // 服务商（ccswitch 式）：可添加多个，一键切换。
    val providers = mutableStateListOf<Provider>()
    var currentProviderId by mutableStateOf<String?>(null)
    val currentProvider: Provider?
        get() = providers.firstOrNull { it.id == currentProviderId }

    val selectedModel: String
        get() = currentProvider?.model?.takeIf { it.isNotBlank() } ?: "未配置"


    init {
        // 埋点落账时若正停在用量 tab 就刷新（onRecorded 在 IO 线程，mutableStateOf 赋值线程安全）
        usageStore?.onRecorded = { if (selectedTab == MainTab.USAGE) refreshUsageData() }
        providers.addAll(providerStore.loadProviders())
        currentProviderId = providerStore.loadCurrentProviderId()?.takeIf { saved ->
            providers.any { it.id == saved }
        }
        // 恢复上次拉取的模型列表：模型页首帧直接显示真实列表，
        // 不再先闪预设兑底名单、拉取完成后又跳变（列表没变时无感）
        modelsFetched = providerStore.loadFetchedModels()
        reasoningEffort = providerStore.loadReasoningEffort()
        // 主题偏好与思考等级一样持久化：进程回收后不再丢回「跟随系统」
        themeChoice = providerStore.loadThemeChoice().takeIf { it.isNotBlank() }
            ?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() }
            ?: ThemeChoice.SYSTEM
    }

    fun changeThemeChoice(v: ThemeChoice) {
        themeChoice = v
        providerStore.saveThemeChoice(v.name)
    }

    fun selectProvider(provider: Provider) {
        currentProviderId = provider.id
        providerStore.saveCurrentProviderId(provider.id)
    }

    fun upsertProvider(provider: Provider) {
        val isNew = providers.none { it.id == provider.id }
        if (isNew) providers.add(provider)
        else providers[providers.indexOfFirst { it.id == provider.id }] = provider
        providerStore.saveProviders(providers)
        // 仅在新增且当前未选中时自动切换；编辑已有服务商（包括非当前的那个）
        // 不改变当前选中——之前 index >= 0 时误把「编辑非当前服务商」变成了切换选中
        if (isNew && (currentProviderId == null || providers.size == 1)) selectProvider(provider)
    }

    fun removeProvider(provider: Provider) {
        providers.removeAll { it.id == provider.id }
        providerStore.saveProviders(providers)
        if (currentProviderId == provider.id) {
            currentProviderId = providers.firstOrNull()?.id
            currentProviderId?.let { providerStore.saveCurrentProviderId(it) }
        }
    }

    fun updateModel(model: String) {
        val provider = currentProvider ?: return
        providers[providers.indexOfFirst { it.id == provider.id }] =
            provider.copy(model = model)
        providerStore.saveProviders(providers)
    }

    val conversations = mutableStateListOf(
        AgentConversation("welcome", "新对话")
    )
    var currentConversationId by mutableStateOf("welcome")
    val currentConversation: AgentConversation
        get() = conversations.firstOrNull { it.id == currentConversationId } ?: conversations.first()
    val currentTitle: String
        get() = currentConversation.title

    // 对话持久化：启动时加载磁盘，之后列表/消息变更时防抖写回。
    // 主题切换等配置变更不再重建 Activity（manifest 已声明 configChanges），
    // 但进程回收/用户杀进程后对话仍在。

    init {
        conversationStore?.load()?.let { snapshots ->
            if (snapshots.isNotEmpty()) applySnapshots(snapshots)
        }
        // 快照写观察 + 轮询防抖（兼顾覆盖面与流畅度）：
        // 全局写观察回调会在“每一次快照写”时触发——包括流式打字机逐帧更新、
        // TypingDots/StreamingCursor 无限动画逐帧写值、抽屉 Animatable 逐帧动画、
        // 列表滚动。回调里绝不能做重活（旧实现在这里每帧构建全量指纹字符串，
        // 动画/流式期间主线程每帧分配大字符串 → 掉帧），只置一个脏标记（零分配）。
        // 真正的指纹比对 + 序列化 + 写盘由 init 里启动的单一轮询协程完成。
        if (conversationStore != null) {
            androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver { _ ->
                persistDirty = true
            }
            engineScope.launch {
                while (isActive) {
                    delay(500)
                    if (!persistDirty) continue
                    persistDirty = false
                    if (persistNow()) autoBackupIfDue()
                }
            }
        }
    }
    private var lastPersistFingerprint: String = ""

    /** 脏标记：写观察回调与显式调用点都只置位，不触发任何分配 */
    @Volatile private var persistDirty = false

    /** 显式调用点（新建/删除/改名等）只需置脏，轮询协程统一处理 */
    fun schedulePersist() { persistDirty = true }

    /** 指纹比对 + 防抖写盘：内容没变的周期直接跳过，避免流式输出每帧写。返回是否真的写盘 */
    private suspend fun persistNow(): Boolean {
        if (conversationStore == null) return false
        val key = conversations.joinToString("\u0001") { it.id + "\u0002" + it.fingerprint() }
        if (key == lastPersistFingerprint) return false
        lastPersistFingerprint = key
        val data = snapshotData()
        // JSON 序列化 + 写盘放 IO 线程：之前在主线程序列化全部对话，
        // 长对话时每 500ms 一次主线程卡顿（流式收尾时最明显）
        withContext(Dispatchers.IO) {
            conversationStore.save(data)
        }
        return true
    }

    /** 当前全部会话 → 纯数据形态（持久化与导出共用同一映射） */
    private fun snapshotData(): List<AgentConversationData> =
        conversations.map { c ->
            // 平铺化分叉树：所有登记过的节点 + 父子关系 + 每个节点的活跃子消息
            val nodes = LinkedHashMap<String, StoredMessage>()
            c.children.forEach { (_, list) ->
                list.forEach { m ->
                    nodes[m.id] = StoredMessage(
                        id = m.id,
                        text = m.text,
                        fromUser = m.fromUser,
                        isError = m.isError,
                        reasoning = m.reasoning,
                        attachments = m.attachments.map { it.uri to it.name }
                    )
                }
            }
            AgentConversationData(
                id = c.id,
                title = c.title,
                titleUserDefined = c.titleIsUserDefined,
                titleUserCount = c.titleUserCount,
                nodes = nodes,
                children = c.children.filterValues { it.isNotEmpty() }.mapValues { (_, list) -> list.map { it.id } },
                activeChild = c.activeChild.toMap()
            )
        }

    // ---- 自动备份：写盘成功后节流复制到 SAF 备份文件夹（卸载重装后的恢复源） ----

    private var lastAutoBackupAt = 0L

    private suspend fun autoBackupIfDue() {
        val saf = safBackup ?: return
        val store = conversationStore ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastAutoBackupAt != 0L && now - lastAutoBackupAt < AUTO_BACKUP_INTERVAL_MS) return
        lastAutoBackupAt = now
        withContext(Dispatchers.IO) {
            // 空快照（新装/清数据后仅剩空 welcome 对话）不写：写进文件夹会把好备份盖掉
            val json = store.readRaw()?.takeIf { store.hasMeaningfulContent(it) } ?: return@withContext
            // 写后读回校验：能解析出对话才算备份成功
            saf.backup(json) { raw -> store.parse(raw).isNotEmpty() }
        }
    }

    // ---- 备份文件夹（SAF）：设置页展示与操作 ----

    /** 备份文件夹状态（未配置/不可访问/最近备份时间），设置页展示用 */
    fun backupFolderStatus(): SafBackupStore.Status =
        safBackup?.status() ?: SafBackupStore.Status(false, false, null, null)

    /** 记录/更换/清除备份文件夹（uri=null 停止自动备份）。返回是否授权成功 */
    fun setBackupFolder(uri: android.net.Uri?): Boolean =
        safBackup?.setTree(uri) ?: false

    /** 当前配置的备份文件夹（未设置为 null）；「从文件夹恢复」取作用 */
    fun backupFolderUri(): android.net.Uri? = safBackup?.configuredTree()

    /** 立即把当前全部对话备份到所选文件夹（手动触发，绕过节流） */
    suspend fun backupToFolderNow(): Boolean {
        val saf = safBackup ?: return false
        val store = conversationStore ?: return false
        // 空快照（新装/清数据后仅剩空 welcome 对话）不写：会覆盖真正的备份
        return withContext(Dispatchers.IO) {
            val json = store.readRaw() ?: return@withContext false
            if (!store.hasMeaningfulContent(json)) return@withContext false
            saf.backup(json) { raw -> store.parse(raw).isNotEmpty() }
        }
    }

    /**
     * 恢复/导入前安全快照：当前对话若有效则存一份进备份文件夹。
     * 恢复错了立刻再恢复一次即可撤销（快照时间最新，排序最前）。
     * 文件夹未配置/不可访问/当前无有效内容时跳过，不阻断恢复。
     */
    suspend fun snapshotCurrentConversations(): Boolean {
        val saf = safBackup ?: return false
        val store = conversationStore ?: return false
        return withContext(Dispatchers.IO) {
            val json = store.readRaw() ?: return@withContext false
            if (!store.hasMeaningfulContent(json)) return@withContext false
            saf.writeSnapshot(json)
        }
    }

    /** 导入/恢复共用：停掉所有生成，整体替换当前列表并立即持久化+备份 */
    private fun replaceAllConversations(snapshots: List<ConversationSnapshot>): Int {
        // 替换前停掉所有生成：旧会话对象即将弃用，协程继续往里写只是白写
        synchronized(streamJobs) { streamJobs.keys.toList() }.forEach { stopGeneration(it) }
        applySnapshots(snapshots)
        lastAutoBackupAt = 0L // 导入后的数据立刻备份一份
        schedulePersist()
        return snapshots.size
    }

    /**
     * 从备份文件夹恢复（卸载重装后的找回路径）：候选按文件修改时间降序扫描
     * （auto 平级才优先，规则钉在 sortRestoreCandidates），取第一份有效的，
     * 先选定再快照后替换 —— 刚写入的快照不能影响本次扫描结果。
     * 返回导入的对话数；0 = 文件夹不可读/没有有效备份。
     */
    suspend fun restoreFromBackupFolder(tree: android.net.Uri): Int {
        val saf = safBackup ?: return 0
        val store = conversationStore ?: return 0
        val entries = withContext(Dispatchers.IO) { saf.listBackups(tree) }
        val byName = entries.associateBy { it.name.lowercase() }
        val candidates = withContext(Dispatchers.IO) {
            SafBackupStore.sortRestoreCandidates(entries.map { it.name to it.lastModified })
                .mapNotNull { name -> byName[name.lowercase()]?.let { saf.readText(it.uri) } }
        }
        val chosen = SafBackupStore.chooseRestore(candidates) { raw ->
            // 空快照不算有效恢复源（旧版/异常写入的空备份不应覆盖当前状态）
            if (store.hasMeaningfulContent(raw)) store.parse(raw) else emptyList()
        } ?: return 0
        val snapshots = store.parse(chosen)
        if (snapshots.isEmpty()) return 0
        snapshotCurrentConversations()
        return replaceAllConversations(snapshots)
    }

    /** 快照 → 会话对象（启动加载与导入共用）：整体替换当前列表并清各会话缓存 */
    private fun applySnapshots(snapshots: List<ConversationSnapshot>) {
        if (snapshots.isEmpty()) return
        conversations.clear()
        inputDrafts.clear()
        attachmentDrafts.clear()
        listStates.clear()
        draftVersions.clear()
        snapshots.forEach { snap ->
            val conv = AgentConversation(snap.id, snap.title)
            conv.titleIsUserDefined = snap.titleUserDefined
            conv.titleUserCount = snap.titleUserCount
            val nodes = LinkedHashMap<String, ChatMessage>()
            snap.tree.nodes.forEach { (id, m) ->
                nodes[id] = ChatMessage(
                    id = id,
                    text = m.text,
                    fromUser = m.fromUser,
                    isError = m.isError,
                    reasoning = m.reasoning,
                    attachments = m.attachments.map { ChatAttachment(it.first, it.second) }
                )
            }
            conv.loadTree(nodes, snap.tree.children, snap.tree.activeChild)
            conversations.add(conv)
        }
        // 清理历史积累的空草稿对话（全是空时保留第一个作当前会话）
        val kept = dropExtraEmptyConversations(conversations.toList())
        if (kept.size != conversations.size) {
            conversations.clear()
            kept.forEach { conversations.add(it) }
            schedulePersist()
        }
        currentConversationId = conversations.first().id
    }

    fun newConversation() {
        // 已有空草稿对话时直接复用（挪到列表顶部），不再堆出一排空「新对话」
        val conversation = findReusableEmptyConversation(conversations)?.let { existing ->
            conversations.remove(existing)
            existing
        } ?: AgentConversation(java.util.UUID.randomUUID().toString(), "新对话")
        conversations.add(0, conversation)
        currentConversationId = conversation.id
        selectedTab = MainTab.CHAT
        schedulePersist()
    }

    fun selectConversation(conversation: AgentConversation) {
        currentConversationId = conversation.id
        selectedTab = MainTab.CHAT
    }

    /** 点会话先关抽屉、后切内容（拆两步）：避免「抽屉退出+内容瞬变」叠在一起。
     * 先记录目标会话，抽屉关到几乎看不见时才真正切换。 */
    var pendingConversationId by mutableStateOf<String?>(null)
    fun deferSelectConversation(conversation: AgentConversation, onCloseDrawer: () -> Unit) {
        if (conversation.id == currentConversationId) { onCloseDrawer(); return }
        pendingConversationId = conversation.id
        onCloseDrawer()
    }
    /** 抽屉开始关闭时调用（AgentApp.closeDrawer 启动时）：新列表在遮挡下完成组合，露出即就位 */
    fun applyPendingConversation() {
        val id = pendingConversationId ?: return
        val conv = conversations.firstOrNull { it.id == id } ?: run { pendingConversationId = null; return }
        pendingConversationId = null
        selectConversation(conv)
    }
    /** 新建对话同样提前到抽屉滑走期间应用：与选会话体验一致 */
    var pendingNewConversation by mutableStateOf(false)
    fun deferNewConversation(onCloseDrawer: () -> Unit) {
        pendingNewConversation = true
        onCloseDrawer()
    }
    fun applyPendingNewConversation() {
        if (!pendingNewConversation) return
        pendingNewConversation = false
        newConversation()
    }

    fun deleteConversation(conversation: AgentConversation) {
        // 若该会话还在流式生成，先停止：否则协程继续往已删除的会话对象里追加消息
        stopGeneration(conversation.id)
        conversations.removeAll { it.id == conversation.id }
        // 清理按会话缓存的草稿/附件/滚动位置，避免长会话列表累积内存
        inputDrafts.remove(conversation.id)
        attachmentDrafts.remove(conversation.id)
        listStates.remove(conversation.id)
        draftVersions.remove(conversation.id)
        if (currentConversationId == conversation.id) {
            currentConversationId = conversations.firstOrNull()?.id
                ?: newConversationInternal().id
        }
        schedulePersist()
    }

    fun renameConversation(conversation: AgentConversation, newTitle: String) {
        conversation.title = newTitle.trim().ifEmpty { "新对话" }
        // 手动命名后标题归用户所有：首题/演化都不再覆盖（CherryStudio 同款保护）
        conversation.titleIsUserDefined = true
        schedulePersist()
    }

    fun changeReasoningEffort(v: String) {
        reasoningEffort = v
        providerStore.saveReasoningEffort(v)
    }

    private fun newConversationInternal(): AgentConversation {
        val conversation = AgentConversation(java.util.UUID.randomUUID().toString(), "新对话")
        // 与 newConversation 一致插到列表头部（抽屉里新对话总在最上面）
        conversations.add(0, conversation)
        return conversation
    }

    companion object {
        /** 自动外部备份最小间隔：流式期间内容高频变化，没必每 500ms 都复制一遍 */
        private const val AUTO_BACKUP_INTERVAL_MS = 10_000L

        /** 手动构造注入：从 AppContainer 取两个 Store，不用反射。 */
        fun factory(container: com.wavex.agent.AppContainer) = viewModelFactory {
            initializer {
                WavexViewModel(
                    container.providerStore,
                    container.conversationStore,
                    container.safBackupStore,
                    container.usageStore
                )
            }
        }
    }
}

/**
 * 统计页一屏数据：进页面/切换范围或筛选/有新落账时整体重算。
 * 聚合在 UsageStore 内存完成（毫秒级），无异步加载态。
 */
data class UsagePageData(
    val summary: com.wavex.agent.data.UsageSummary,
    val providerStats: List<com.wavex.agent.data.ProviderStat>,
    val modelStats: List<com.wavex.agent.data.ModelStat>,
    val trends: List<com.wavex.agent.data.TrendPoint>,
    val recentLogs: List<com.wavex.agent.data.UsageLogEntry>
) {
    companion object {
        val EMPTY = UsagePageData(
            com.wavex.agent.data.UsageSummary(0, 0, 0, 0),
            emptyList(), emptyList(), emptyList(), emptyList()
        )
    }
}
