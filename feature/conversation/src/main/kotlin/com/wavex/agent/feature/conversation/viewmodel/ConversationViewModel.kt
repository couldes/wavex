package com.wavex.agent.feature.conversation.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavex.agent.core.domain.chat.ContentLoader
import com.wavex.agent.core.domain.chat.FallbackAction
import com.wavex.agent.core.domain.chat.FallbackFlags
import com.wavex.agent.core.domain.chat.FallbackPolicy
import com.wavex.agent.core.domain.chat.HistoryBuilder
import com.wavex.agent.core.domain.chat.ReasoningLevels
import com.wavex.agent.core.domain.chat.ResponseImageExtractor
import com.wavex.agent.core.domain.model.AgentConversationData
import com.wavex.agent.core.domain.model.AttachmentWriteResult
import com.wavex.agent.core.domain.model.ChatAttachment
import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ConversationSnapshot
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.StoredMessage
import com.wavex.agent.core.domain.policy.TitlePolicy
import com.wavex.agent.core.domain.port.ConversationRepository
import com.wavex.agent.core.domain.port.GeneratedFileWriter
import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.core.domain.port.ProviderSettingsRepository
import com.wavex.agent.core.domain.model.StreamErrorCode
import com.wavex.agent.core.domain.model.streamFailureText
import com.wavex.agent.feature.conversation.model.AgentConversation
import com.wavex.agent.feature.conversation.state.bumpByActivity
import com.wavex.agent.feature.conversation.state.dropExtraEmptyConversations
import com.wavex.agent.feature.conversation.state.findReusableEmptyConversation
import com.wavex.agent.feature.conversation.state.reorderByLastActive
import com.wavex.agent.feature.conversation.state.setArchived
import com.wavex.agent.feature.conversation.state.setPinned
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 发往 UI 的一次性通知（计划 Task 6：以类型化效果替代 Toast；由功能 UI / app 壳渲染）。
 */
sealed interface ConversationEffect {
    data class Notice(val text: String, val longDuration: Boolean = true) : ConversationEffect
}

/**
 * 会话状态与生成引擎（从 WavexViewModel 整体迁出，Task 6）：
 * - 会话列表/当前会话/草稿/滚动位置等会话域状态；
 * - 生成引擎：构建历史 → SSE 流式 → 打字机帧 → 收尾提取落盘；每会话一条协程可并发；
 * - 标题生成/演化（TitlePolicy 闸门）；
 * - 持久化协调：脏标记 + 全局写观察 + 500ms 轮询 → ConversationRepository.sync。
 *
 * 无 Android Context、无 Toast、无存储实现、无 MainTab/ThemeChoice 依赖；
 * 服务商在每次生成/标题发起前经 ProviderSettingsRepository.loadCurrentProvider() 现取，
 * 目录页的增删改即时可见，且不需要 feature 间 ViewModel 依赖。
 */
class ConversationViewModel(
    private val repository: ConversationRepository,
    private val providerSettings: ProviderSettingsRepository,
    private val modelService: ModelService,
    private val contentLoader: ContentLoader,
    private val generatedFileWriter: GeneratedFileWriter,
) : ViewModel() {

    // ---------- 会话列表与当前会话 ----------

    val conversations = mutableStateListOf(AgentConversation("welcome", "新对话"))
    var currentConversationId by mutableStateOf("welcome")
    val currentConversation: AgentConversation
        get() = conversations.firstOrNull { it.id == currentConversationId } ?: conversations.first()
    val currentTitle: String
        get() = currentConversation.title

    // 每个对话独立的输入草稿 / 附件草稿 / 滚动位置
    val inputDrafts = mutableStateMapOf<String, String>()
    val attachmentDrafts = mutableStateMapOf<String, SnapshotStateList<ChatAttachment>>()
    val listStates = mutableStateMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
    // listStates 中「尚未经 ChatScreen 首帧落位」的会话：全新 LazyListState 的位置 0
    // 是默认值而非用户留下的阅读位置，首个可判定帧不按几何播种意图、直接钉底
    // （详见 ChatScreen 布局观察者）。非快照态：只在观察者协程里读、不驱动重组。
    private val pristineListStates = mutableSetOf<String>()
    // 外部写入草稿（系统分享文本）时自增，通知 ChatScreen 重新读草稿到输入框
    val draftVersions = mutableStateMapOf<String, Int>()

    fun draftInputFor(conversationId: String): String = inputDrafts[conversationId] ?: ""
    fun setDraftInput(conversationId: String, value: String) {
        if (value.isBlank()) inputDrafts.remove(conversationId) else inputDrafts[conversationId] = value
    }
    fun attachmentsFor(conversationId: String): SnapshotStateList<ChatAttachment> =
        attachmentDrafts.getOrPut(conversationId) { mutableStateListOf() }
    fun listStateFor(conversationId: String): androidx.compose.foundation.lazy.LazyListState {
        listStates[conversationId]?.let { return it }
        pristineListStates.add(conversationId)
        return androidx.compose.foundation.lazy.LazyListState().also { listStates[conversationId] = it }
    }

    /** 该会话的 listState 是否从未经过首帧落位（true = 位置仍是默认值，非用户意图）。 */
    fun isListStatePristine(conversationId: String): Boolean = pristineListStates.contains(conversationId)

    /** 首帧落位完成：此后该会话的列表位置代表真实阅读位置，切回时按几何恢复。 */
    fun markListStateSettled(conversationId: String) {
        pristineListStates.remove(conversationId)
    }

    // ---------- 设置联动（生成参数） ----------

    // 思考等级（持久化）与联网搜索开关（会话级）
    var reasoningEffort by mutableStateOf("")
    var webSearch by mutableStateOf(false)

    /** 生成/标题发起时现取的当前服务商（send 校验与 UI 展示用；目录页改动即时可见） */
    var selectedProvider by mutableStateOf<Provider?>(null)
        private set

    /** 当前服务商的模型展示名（抽屉头部「已连接」状态用）；未配置时回退占位文案 */
    val selectedModel: String
        get() = selectedProvider?.model?.takeIf { it.isNotBlank() } ?: "未配置"

    /**
     * 目录页增删改服务商后的现取重载：顶栏/抽屉的模型名即时更新。
     * 仍以共享存储为唯一事实源（app 壳在观察到目录页状态变化时调用），
     * 不引入跨 feature 的 ViewModel 依赖。
     */
    fun refreshSelectedProvider() {
        selectedProvider = providerSettings.loadCurrentProvider()
    }

    /** 点会话先关抽屉、后切内容（拆两步）：避免「抽屉退出+内容瞬变」叠在一起 */
    var pendingConversationId by mutableStateOf<String?>(null)
    /** 新建对话同样提前到抽屉滑走期间应用：与选会话体验一致 */
    var pendingNewConversation by mutableStateOf(false)

    // ---------- 一次性 UI 效果（替代 Toast） ----------

    private val _effects = MutableSharedFlow<ConversationEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<ConversationEffect> = _effects.asSharedFlow()

    private fun notify(text: String, longDuration: Boolean) {
        _effects.tryEmit(ConversationEffect.Notice(text, longDuration))
    }

    // 生成引擎提升到全局状态：切 Tab/主题时对话页可安全离开组合树，
    // 流式协程不依附于某个页面的生命周期。viewModelScope 默认即
    // Dispatchers.Main.immediate + SupervisorJob，语义与原 engineScope 一致。
    private val engineScope get() = viewModelScope

    /** 收尾提取/落盘的 IO 派发（可注入以便 JVM 单测用虚拟调度器替换，避免真实线程池等待） */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    /** 持久化快照构建的派发（可注入以便 JVM 单测替换）：全量平铺是流式期间的大分配热点，
     *  移到后台后主线程只承担 sync 的挂起与恢复 */
    internal var snapshotDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

    init {
        reasoningEffort = ReasoningLevels.normalize(providerSettings.loadReasoningEffort())
        // 顶栏/抽屉首帧展示当前服务商模型名：init 现取一次；此后目录页改动由
        // app 壳观察目录 VM 变化调 refreshSelectedProvider() 即时重载（生成/标题
        // 发起时仍照旧现取，兜底覆盖所有写入路径）
        selectedProvider = providerSettings.loadCurrentProvider()
        // 会话加载（per-session JSONL，spec §5.1）：迁移 → loadAll → 基线登记 → 孤儿清扫
        // 全部封在 repository 内（IO 线程），VM 只做 applySnapshots 回主线程；轮询协程最后才
        // 启动 —— 加载完成前不存在任何写盘路径，消除「半加载态被轮询落盘」的竞态。
        engineScope.launch {
            applySnapshots(repository.loadAll())
            // 基线/known-id 已在 repository 内对全部解析出的会话登记，就位后才启动轮询
            startStoragePolling()
        }
    }

    fun changeReasoningEffort(v: String) {
        reasoningEffort = v
        providerSettings.saveReasoningEffort(v)
    }

    // ---------- 生成状态跟踪 ----------

    // 按会话跟踪生成状态：多个对话可同时流式（A 生成时切到 B，B 照常能发）。
    // activeGenerations 是 Compose 可观察集合，不能再用单个 generatingIn 覆盖不同会话。
    private val activeGenerations = mutableStateMapOf<String, Boolean>()
    private val streamJobs = mutableMapOf<String, Job>()

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
    fun startGeneration(conversation: AgentConversation) {
        // 发送/重发即活跃：会话顶到「最近对话」首位（重试也覆盖）
        conversations.bumpByActivity(conversation, System.currentTimeMillis())
        // 同一对话不叠加生成；不同对话各自开一条流（并发）
        if (activeGenerations.containsKey(conversation.id)) return
        // 目录页的增删改即时可见：不在 VM 里缓存 provider，发起时现取
        val provider = providerSettings.loadCurrentProvider() ?: return
        selectedProvider = provider
        val messages = conversation.messages
        val convId = conversation.id
        // 先登记再启动协程，防止快速双击/切回同一会话时追加第二条用户消息。
        activeGenerations[convId] = true
        val job = engineScope.launch {
            val assistantIndex = messages.size
            // 占位气泡的索引：若插入了「不支持的附件」提示，占位会被推后一位
            var placeholderIndex = assistantIndex
            // 正文/思考缓冲提升到 try 外：取消清理（尾部未闭合 data URL → 中断占位）需要读 target
            val target = StringBuilder()
            val reasoningBuf = StringBuilder()
            // 流式缓冲跨线程锁：onDelta 在 IO 线程（streamChat 的 withContext(IO)）追加，
            // 打字机在主线程每帧读长度并 substring。StringBuilder 非线程安全，且两线程间
            // 无任何 happens-before（ARM 设备可能读到未写完的字符/陈旧长度）。
            // 读写全部过这把锁：每 delta / 每帧各一次，纳秒级开销。
            val bufLock = Any()
            // 请求参数提升到 try 外：catch(StreamErrorCode) 的 streamFailureText 需要读取
            //（Kotlin 中 try 块内声明的局部量在 catch 不可见）。
            // 每次都带参数发送：被拒时按参数逐个降级重试并各自提示。
            //（曾做过“记住被拒参数”的缓存，但中转站报错文案千奇百怪难维护，已弃用）
            val effortParam: String? = reasoningEffort.ifBlank { null }
            val webParam: Boolean = webSearch
            // D-01 修复：占位的所有权按消息 id 而非快照索引。停止竞态下
            //（A1 收尾删除/子链过继导致后续消息索引位移），打字机帧与收尾
            // 全部按 id 重查位置；查不到（占位已被清理）则跳过写入。
            // 声明在 try 外（catch 需引用；Kotlin 中 try 内局部量在 catch 不可见）
            var placeholderId: String? = null
            fun ph(): Int = placeholderId?.let { conversation.indexOfMessage(it) } ?: -1
            try {
                // 历史构建外移到 engine/HistoryBuilder：每条用户消息独立加载附件
                // （图片→视觉消息、音频→input_audio、文本→内联），多轮发图不丢图
                val built = HistoryBuilder.build(
                    messages.take(assistantIndex),
                    contentLoader
                )
                val history = built.history
                built.unreadableName?.let {
                    // 软件能收的格式挑选时不拦（见 loadContent），能到这里只剩文件失效
                    conversation.appendMessage(ChatMessage(text = "无法读取「$it」的内容，文件可能已被移动或删除", fromUser = false, isError = true))
                }
                conversation.appendMessage(ChatMessage(text = "", fromUser = false))
                placeholderIndex = messages.size - 1
                placeholderId = messages.last().id
                // 登记流式占位（spec §5.3）：登记期间 diff 对该 id 的增/改/删与指向它的
                // active 全部跳过，占位增长不留任何文件痕迹；finally 清除后按「新节点」
                // 路径一次落盘最终内容（suppress id 不进 next.nodes，基线里没有它）
                suppressUpsert[convId] = placeholderId

                var shown = 0
                // 思考过程单独缓冲：qwen3/gpt-6 等会先流式吐 reasoning_content 再吐正文，
                // 存进 ChatMessage.reasoning，由气泡渲染成可折叠区块（正文到达后自动收起）
                var reasoningShown = 0
                // 结构化图片（delta.images[]）暂存：流式期间只收集，收尾统一落盘进附件
                val imageUrls = mutableListOf<String>()
                // 正文 data URL 流式掩码状态：区域列表 + 已扫描位置（每帧只扫新增尾部）
                var maskRegions: List<IntRange> = emptyList()
                var maskScanned = 0
                val typewriter = launch {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        // 自适应节奏：中转站常把大段内容塞进少数 chunk（几十上百字/chunk），
                        // 固定 28%/帧永远追不上 → 观感「卡一下全屏出」。
                        // 落后量大时降帧率（16→32→48→64ms）、步长加大（28%→45%），
                        // 少量落后时保持原来的 16ms/28% 手感。
                        // 长度快照过锁（lag 只用于节奏，近似值即可）
                        val (tLen, rLen) = synchronized(bufLock) { target.length to reasoningBuf.length }
                        val lag = (tLen - shown) + (rLen - reasoningShown)
                        kotlinx.coroutines.delay(
                            when {
                                lag > 800 -> 64L
                                lag > 300 -> 48L
                                lag > 120 -> 32L
                                else -> 16L
                            }
                        )
                        // 整帧内容在锁内构建：substring 读到的字符数据保证已完整写入
                        val frame = synchronized(bufLock) {
                            val full = target.length
                            val rFull = reasoningBuf.length
                            if (shown >= full && reasoningShown >= rFull) return@synchronized null
                            // 步长随落后量自适应（至少 1 字）：温和渐进 vs 快速追赶
                            val big = lag > 300
                            val step = maxOf(1, ((full - shown) * (if (big) 0.45f else 0.28f)).toInt())
                            shown = minOf(full, shown + step)
                            val rStep = maxOf(1, ((rFull - reasoningShown) * (if (big) 0.45f else 0.28f)).toInt())
                            reasoningShown = minOf(rFull, reasoningShown + rStep)
                            // 正文 data URL 流式掩码：未闭合/闭合 URL 区域显示「生成中」占位，
                            // 避免图片 base64 随打字机节奏闪现乱码
                            val prefix = target.substring(0, shown)
                            maskRegions = ResponseImageExtractor.updateRegions(prefix, maskRegions, maskScanned)
                            maskScanned = shown
                            ResponseImageExtractor.applyMask(prefix, maskRegions) to
                                reasoningBuf.substring(0, reasoningShown)
                        }
                        frame?.let { (text, reasoning) ->
                            val idx = ph()
                            if (idx >= 0) conversation.updateMessageAt(
                                idx,
                                messages[idx].copy(text = text, reasoning = reasoning)
                            )
                        }
                    }
                }
                val collectDeltas: (String, String) -> Unit = { delta, reasoningDelta ->
                    synchronized(bufLock) {
                        if (reasoningDelta.isNotEmpty()) reasoningBuf.append(reasoningDelta)
                        if (delta.isNotEmpty()) target.append(delta)
                    }
                }
                // 递归降级：音频 → 图片 → 思考等级 → 联网搜索，逐层剔除被模型拒绝的部分。
                // 错误分类：isModelError(msg) = 模型不收这类内容（格式本身没问题，软件能处理）；
                // 其余 HTTP 4xx = 参数不支持。每次降级以类型化效果提示用户原因与处理方式
                suspend fun send(h: List<ChatRequestMessage>, effort: String?, web: Boolean, audioDropped: Boolean, imageDropped: Boolean, pdfDropped: Boolean = false) {
                    try {
                        modelService.streamChat(provider, h, reasoningEffort = effort, webSearch = web, onDelta = collectDeltas, onImage = { imageUrls.add(it) })
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        // 降级决策外移到 engine/FallbackPolicy（关键词逐字保留）；重发与提示留在这里
                        val flags = FallbackFlags(
                            historyHasAudio = built.historyHasAudio,
                            historyHasImage = built.historyHasImage,
                            historyHasPdf = built.historyHasPdf,
                            audioDropped = audioDropped,
                            imageDropped = imageDropped,
                            pdfDropped = pdfDropped,
                            effort = effort,
                            web = web
                        )
                        when (FallbackPolicy.decide(msg, flags)) {
                            FallbackAction.DropAudio -> {
                                notify("当前模型不支持音频输入，已忽略音频继续回答", longDuration = true)
                                // D-03 修复：基于当前轮历史 h 累计剔除，而非从原始历史重建
                                //（否则第二次降级时已拒音频会重新出现，链断裂）
                                send(built.withFallbackNote(built.dropAudio(h)), effort, web, audioDropped = true, imageDropped)
                            }
                            FallbackAction.DropImage -> {
                                notify("当前模型不支持图片输入，已忽略图片继续回答（如需看图请换支持视觉的模型）", longDuration = true)
                                send(built.withFallbackNote(built.dropImage(h)), effort, web, audioDropped, imageDropped = true)
                            }
                            FallbackAction.DropPdf -> {
                                notify("当前模型不支持 PDF 输入，请改用图片发送或换支持的模型", longDuration = true)
                                send(built.withFallbackNote(built.dropPdf(h)), effort, web, audioDropped, imageDropped, pdfDropped = true)
                            }
                            FallbackAction.DropEffort -> {
                                notify("当前模型不支持所选思考等级，已忽略该设置继续回答", longDuration = false)
                                send(h, null, web, audioDropped, imageDropped, pdfDropped = pdfDropped)
                            }
                            FallbackAction.DropWeb -> {
                                notify("当前模型不支持联网搜索，已忽略该设置继续回答", longDuration = false)
                                // 保留 effort：联网被拒不代表思考等级被拒，之前误传 null 把思考等级也静默丢掉
                                send(h, effort, false, audioDropped, imageDropped, pdfDropped = pdfDropped)
                            }
                            FallbackAction.None -> throw e
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
                // 收尾：全部显示 + 图片/文件提取落盘。结构化 images（delta.images）按到达序在前，
                // 正文提取件按出现序在后；base64 类经 GeneratedFileWriter 落盘。
                // 提取+写盘放 IO 线程（spec §6）：大图解码写盘数百毫秒，主线程执行会掉帧；
                // 打字机已取消、流已结束，withContext 切换提供内存栅栏，target/imageUrls 快照读取安全
                var structuredFailed = 0
                var structuredTooLarge = 0
                var structuredSaved = 0
                val (finalText, atts) = kotlinx.coroutines.withContext(ioDispatcher) {
                    val writeFile: (String, String, String) -> AttachmentWriteResult =
                        { mime, b64, name -> generatedFileWriter.write(mime, b64, name) }

                    // 结构化图（delta.images）专用计数包装：structuredToAttachment 对落盘失败/超限
                    // 返回 null 且不留任何痕迹（正文内嵌图失败会留占位，结构化图不会），
                    // 若不计数补占位，纯图回复会静默变成空气泡（gemini 发附件实测）
                    val trackedWrite: (String, String, String) -> AttachmentWriteResult =
                        { mime, b64, name ->
                            val outcome = writeFile(mime, b64, name)
                            when (outcome) {
                                is AttachmentWriteResult.Saved -> structuredSaved++
                                AttachmentWriteResult.Failed -> structuredFailed++
                                AttachmentWriteResult.TooLarge -> structuredTooLarge++
                            }
                            outcome
                        }
                    val extraction = ResponseImageExtractor.process(
                        // 过锁快照：与打字机/append 的剩余竞态收口（打字机已取消，此为兜底）
                        synchronized(bufLock) { target.toString() },
                        writeFile
                    )
                    val structured = imageUrls.mapIndexedNotNull { i, u ->
                        ResponseImageExtractor.structuredToAttachment(u, i + 1, trackedWrite)
                    }
                    val list = structured.map { ChatAttachment(uri = it.uri, name = it.displayName) } +
                        extraction.files.map { ChatAttachment(uri = it.uri, name = it.displayName) }
                    // 结构化图（delta.images）不经过正文，提取占位覆盖不到：
                    // 成功项文件名以代码片行补进正文（与提取占位同形态，图文混合时正文有名字锚点）；
                    // 失败/超限与 process() 失败态同款占位补进正文，绝不静默丢成空气泡
                    var text = ResponseImageExtractor.withNotes(
                        extraction.text,
                        ResponseImageExtractor.structuredNotes(structured.map { it.displayName })
                    )
                    repeat(structuredFailed) {
                        text = ResponseImageExtractor.withNotes(text, "[图片：保存失败]")
                    }
                    repeat(structuredTooLarge) {
                        text = ResponseImageExtractor.withNotes(text, "[附件：过大未保存]")
                    }
                    // 结构化数据被识别但无法成附件（URL 非 data:/http(s) 形态）：正文非空时
                    // 不再静默丢弃——与空正文的下方诊断同口径补计数注记（只记数量，不记内容）。
                    // 没有这行，网关发附件形态异常时唯一的现场证据就没了
                    val unknown = imageUrls.size - structuredSaved - structuredFailed - structuredTooLarge
                    if (unknown > 0 && text.isNotBlank()) {
                        text = ResponseImageExtractor.withNotes(
                            text,
                            "（模型返回了 $unknown 条无法识别的图片/文件数据，已丢弃）"
                        )
                    }
                    text to list
                }
                // 收尾统一按 id 重查占位位置（快照索引在停止竞态下不可信）
                val doneIdx = ph()
                if (doneIdx >= 0) messages.getOrNull(doneIdx)?.let {
                    conversation.updateMessageAt(doneIdx, it.copy(text = finalText, reasoning = reasoningBuf.toString(), attachments = atts))
                }
                if (ph().let { it >= 0 && messages[it].text.isNullOrBlank() && messages[it].reasoning.isNullOrBlank() && atts.isEmpty() }) {
                    // 模型返回过图片数据但全部无法成附件（结构化 URL 非 data:/http(s)）：
                    // 带上计数诊断，不再是干巴巴的「没有返回内容」（保存失败/超大已在正文留占位，
                    // 不会进本分支——能到这里只剩无法识别的 URL）
                    val emptyIndex = ph()
                    val unknown = imageUrls.size - structuredSaved - structuredFailed - structuredTooLarge
                    val emptyText = if (imageUrls.isNotEmpty() && unknown > 0) {
                        "（模型没有返回内容；返回了 ${imageUrls.size} 条图片数据，其中 $unknown 条格式无法识别）"
                    } else {
                        "（模型没有返回内容）"
                    }
                    // 必须用 copy() 保留原 id：updateMessageAt 按 id 同步树节点，
                    // 换成新建 ChatMessage（新 id）时路径上显示报错文案、树里仍是空占位，
                    // 切分支/重启后由树重建路径 → 变回空气泡（实测：联网空流后切分支）
                    messages.getOrNull(emptyIndex)?.let {
                        conversation.updateMessageAt(
                            emptyIndex,
                            it.copy(text = emptyText, isError = true)
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点了停止：保留已生成的部分；完全没内容则移除占位并切回旧分支。
                // 尾部未闭合 data URL 收尾成中断占位（不残留乱码）；已完整但未提取的
                // URL 残留为已知局限（见计划 Review Focus）
                // D-01 修复：按 id 删除（removeMessageById 子链过继），停止后新发
                // 消息的父链挂在占位下也不会被误删/孤儿化
                val cancelIdx = ph()
                if (cancelIdx >= 0 && messages[cancelIdx].text.isNullOrBlank()) {
                    placeholderId?.let { conversation.removeMessageById(it) }
                } else if (cancelIdx >= 0) {
                    messages.getOrNull(cancelIdx)?.let {
                        conversation.updateMessageAt(
                            cancelIdx,
                            it.copy(
                                text = synchronized(bufLock) {
                                    ResponseImageExtractor.stripIncomplete(target.toString())
                                }
                            )
                        )
                    }
                }
            } catch (e: StreamErrorCode) {
                // 空流/超时/分片不可识别：联网感知的人话提示（网络层注释约定的接线，
                // 此前缺失导致落到通用 catch 变成「请求失败：…」）；
                // EmptyContent 的 underlying 含分片形状指纹（如有）
                val errIdx = ph()
                if (errIdx >= 0) messages.getOrNull(errIdx)?.let {
                    conversation.updateMessageAt(
                        errIdx,
                        it.copy(
                            text = streamFailureText(e, webParam, effortParam),
                            isError = true
                        )
                    )
                }
            } catch (e: Exception) {
                val msg = e.message?.take(300) ?: "网络错误"
                val failIdx = ph()
                if (failIdx >= 0) messages.getOrNull(failIdx)?.let {
                    conversation.updateMessageAt(failIdx, it.copy(
                        text = "请求失败：$msg",
                        isError = true
                    ))
                }
            } finally {
                streamJobs.remove(convId)
                activeGenerations.remove(convId)
                // 解除占位抑制（取消时同样执行）：下一轮 diff 把占位的最终内容/删除
                // 按普通新节点或无痕迹（从未落盘）处理
                suppressUpsert.remove(convId)
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
        val provider = providerSettings.loadCurrentProvider() ?: return
        selectedProvider = provider
        titleInFlight.add(conversation.id)
        engineScope.launch {
            try {
                val generated = modelService.generateTitle(provider, transcript)
                if (generated != null) {
                    // D-02 修复：标题请求等待期间用户可能手动改名（闸门只在发起前检查过），
                    // 迟到的生成标题必须让位于手动命名
                    if (conversation.titleIsUserDefined) return@launch
                    conversation.title = generated
                } else if (conversation.title == "新对话") {
                    // 首次生成失败：回退首条消息截断；演化失败不动旧题
                    val firstUser = conversation.messages.firstOrNull { it.fromUser && !it.isError }
                    conversation.title = firstUser?.text?.take(28)?.ifBlank {
                        firstUser.attachments.firstOrNull()?.let { attachmentDisplayNameOf(it.name).take(20) } ?: "新对话"
                    } ?: "新对话"
                } else return@launch   // 演化失败：基线不推进，下个里程碑自然重试
                conversation.titleUserCount = conversation.messages.count { it.fromUser && !it.isError }
                schedulePersist()
            } finally {
                titleInFlight.remove(conversation.id)
            }
        }
    }

    /** 展示名格式化（core:ui 附件文案助手；标题回退用） */
    private fun attachmentDisplayNameOf(name: String): String =
        com.wavex.agent.core.ui.attachmentDisplayName(name)

    // ---------- 持久化协调（写路径全部在 repository） ----------

    /** 脏标记：写观察回调与显式调用点都只置位，不触发任何分配 */
    @Volatile private var persistDirty = false

    /** 显式调用点（新建/删除/改名等）只需置脏，轮询协程统一处理 */
    fun schedulePersist() { persistDirty = true }

    /** convId → 流式占位消息 id：登记期间 diff 对该 id 的增/改/删与指向它的 active 全部跳过。
     *  ConcurrentHashMap：主线程生成协程写（登记/finally 清除），轮询 IO 线程读。
     *  这是生成期状态，留在 VM；基线/单写者锁/在盘 id 表都在 repository 内。 */
    private val suppressUpsert = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 注册全局写观察器并启动轮询：只在加载（repository.loadAll → applySnapshots）完成后调用。
     *  轮询体 best-effort（fix round 2 #3）：单拍失败记日志并置回脏标记，下一拍重试，轮询不因 IO 异常退出。 */
    private fun startStoragePolling() {
        // 写观察回调在每一次快照写时触发（流式打字机逐帧、动画逐帧、滚动），
        // 只置脏标记（零分配）；真正的 diff + 追加写盘由下面的单一轮询协程统一完成
        androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver { _ ->
            persistDirty = true
        }
        engineScope.launch {
            while (isActive) {
                delay(500)
                if (!persistDirty) continue
                persistDirty = false
                try {
                    // 快照构建移出主线程（Default 池）：轮询拍在脏时要把所有会话的整棵消息树
                    // （含全部分支）平铺成分叉树数据 —— 流式期间打字机逐帧写快照置脏，本拍
                    // 最长每 500ms 触发一次，全量分配留在主线程会周期性顶高 GC 压力，
                    // 表现为流式打字与切换会话时的可感掉帧。
                    // 后台线程只读已提交的全局快照（无写路径），视图跨提交略有交错也安全：
                    // diff 恒以 repository 内基线为准，漏掉的变化下一拍自然补上。
                    val snapshots = kotlinx.coroutines.withContext(snapshotDispatcher) {
                        conversations.map { snapshotOf(it) }
                    }
                    repository.sync(snapshots, suppressUpsert.toMap())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e  // 协程取消（VM 销毁）照常传播，不按普通失败吞掉
                } catch (e: Exception) {
                    // appendLines/deleteSession 的 IO 异常（权限收回/磁盘满）不能杀死轮询协程：
                    // 未捕获在主线程 → 默认 handler → 崩进程；吞掉不重试 → 持久化静默停摆。
                    // 记日志并恢复脏标记，下一拍重试（diff 恒以 repository 内基线为准，重跑幂等）
                    android.util.Log.w("ConversationViewModel", "session flush failed; retrying next tick", e)
                    persistDirty = true
                }
            }
        }
    }

    /** 单会话 → 纯数据形态（平铺化分叉树：所有登记过的节点 + 父子关系 + 各父活跃孩子） */
    private fun snapshotOf(c: AgentConversation): AgentConversationData {
        val nodes = LinkedHashMap<String, StoredMessage>()
        c.children.forEach { (_, list) ->
            list.forEach { m ->
                nodes[m.id] = StoredMessage(
                    id = m.id,
                    text = m.text,
                    fromUser = m.fromUser,
                    isError = m.isError,
                    reasoning = m.reasoning,
                    attachments = m.attachments.map { it.uri to it.name },
                    // 工具调用必须搬：这里是 ChatMessage → StoredMessage 的唯一转换点，
                    // 漏搬会让工具调用只活在内存里，重启即丢
                    toolCalls = m.toolCalls,
                    toolCallId = m.toolCallId,
                )
            }
        }
        return AgentConversationData(
            id = c.id,
            title = c.title,
            titleUserDefined = c.titleIsUserDefined,
            titleUserCount = c.titleUserCount,
            lastActiveAt = c.lastActiveAt,
            pinned = c.pinned,
            pinnedAt = c.pinnedAt,
            archived = c.archived,
            nodes = nodes,
            children = c.children.filterValues { it.isNotEmpty() }.mapValues { (_, list) -> list.map { it.id } },
            activeChild = c.activeChild.toMap()
        )
    }

    // ---------- 快照装载与导入 ----------

    /** 快照 → 会话对象（启动加载与旧备份导入共用）：树节点平铺转 ChatMessage 后 loadTree 重建路径 */
    private fun conversationFromSnapshot(snap: ConversationSnapshot): AgentConversation {
        val conv = AgentConversation(snap.id, snap.title)
        conv.titleIsUserDefined = snap.titleUserDefined
        conv.titleUserCount = snap.titleUserCount
        conv.lastActiveAt = snap.lastActiveAt
        conv.pinned = snap.pinned
        conv.pinnedAt = snap.pinnedAt
        conv.archived = snap.archived
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
        return conv
    }

    /** 快照 → 会话列表（启动加载）：整体替换当前列表并清各会话缓存 */
    private fun applySnapshots(snapshots: List<ConversationSnapshot>) {
        if (snapshots.isEmpty()) return
        conversations.clear()
        inputDrafts.clear()
        attachmentDrafts.clear()
        listStates.clear()
        pristineListStates.clear()
        draftVersions.clear()
        snapshots.forEach { snap -> conversations.add(conversationFromSnapshot(snap)) }
        // 清理历史积累的空草稿对话（全是空时保留第一个作当前会话）
        val kept = dropExtraEmptyConversations(conversations.toList())
        if (kept.size != conversations.size) {
            conversations.clear()
            kept.forEach { conversations.add(it) }
            schedulePersist()
        }
        // 恢复排序不变量：lastActiveAt 降序；旧数据等值 0 → 稳定排序保持文件序（创建序降序）
        conversations.reorderByLastActive()
        currentConversationId = conversations.first().id
        // 全部会话都已归档时不能停在归档会话上：新建空草稿作为当前会话（抽屉主列表才不是空的）
        ensureCurrentConversationVisible()
    }

    /**
     * 旧备份导入的会话域入口（SAF 流读取留在 app 壳；此处只收 domain 快照）：
     * 逐会话分配全新 UUID → repository.importSessions 落盘登记 → 成功后同一次主线程续体内
     * 加进列表（无挂起点，与轮询无交错，刚导入的文件不会被 sync 当孤儿删掉）。
     * 部分导入语义：某会话写失败（repo 内 best-effort 删残文件）→ 停止并返回已成功数。
     */
    suspend fun importSnapshots(snapshots: List<ConversationSnapshot>): Int {
        if (snapshots.isEmpty()) return 0
        var imported = 0
        for (snap in snapshots) {
            val newId = java.util.UUID.randomUUID().toString()
            val data = AgentConversationData(
                id = newId,
                title = snap.title,
                titleUserDefined = snap.titleUserDefined,
                titleUserCount = snap.titleUserCount,
                lastActiveAt = snap.lastActiveAt,
                pinned = snap.pinned,
                pinnedAt = snap.pinnedAt,
                archived = snap.archived,
                nodes = snap.tree.nodes,
                children = snap.tree.children,
                activeChild = snap.tree.activeChild,
            )
            if (repository.importSessions(listOf(data)) == 0) return imported
            // 成功后同一次主线程续体内加进列表；带活跃时间的导入会话按序落位，
            // 旧格式（lastActiveAt=0）稳定排到现有之下
            conversations.add(conversationFromSnapshot(snap.copy(id = newId)))
            conversations.reorderByLastActive()
            imported++
        }
        return imported
    }

    // ---------- 会话列表操作 ----------

    fun newConversation() {
        // 已有空草稿对话时直接复用，不再堆出一排空「新对话」；
        // 复用是点击不是使用：不刷新活跃时间、不移动位置
        val reusable = findReusableEmptyConversation(conversations)
        if (reusable != null) {
            currentConversationId = reusable.id
            schedulePersist()
            return
        }
        // 全新会话按创建时刻落位未置顶区头部；首次发送（startGeneration）才刷新活跃置顶
        val conversation = AgentConversation(java.util.UUID.randomUUID().toString(), "新对话")
        conversations.bumpByActivity(conversation, conversation.lastActiveAt)
        currentConversationId = conversation.id
        schedulePersist()
    }

    fun selectConversation(conversation: AgentConversation) {
        // 仅切换当前会话：点击不算使用，不刷新活跃时间、不重排列表
        //（活跃只在 startGeneration 发送/重发时刷新，抽屉排序与日期分组随之稳定）
        currentConversationId = conversation.id
    }

    /** 置顶/取消置顶：置顶区固定在列表头部（区内按置顶时刻降序），列表写同时触发落盘 */
    fun setPinnedConversation(conversation: AgentConversation, pinned: Boolean) {
        conversations.setPinned(conversation, pinned, System.currentTimeMillis())
    }

    /** 归档/取消归档：移出主列表、可搜索命中；归档即取消置顶，列表写同时触发落盘 */
    fun setArchivedConversation(conversation: AgentConversation, archived: Boolean) {
        conversations.setArchived(conversation, archived, System.currentTimeMillis())
        // 归档的正是当前会话：立即切走，否则问答写进抽屉里看不见的会话（用户以为内容丢了）
        if (archived) ensureCurrentConversationVisible()
    }

    /** 批量归档：逐个走单条归档链路（自动取消置顶、移入归档区）；按列表倒序归档，归档区内保持原相对顺序 */
    fun archiveConversations(ids: Collection<String>) {
        conversations.filter { it.id in ids }.reversed()
            .forEach { conversations.setArchived(it, true, System.currentTimeMillis()) }
        ensureCurrentConversationVisible()
    }

    /**
     * 当前会话兜底：当前会话已被归档（或已不在列表）时切到最近的未归档会话；
     * 一个都没有时新建空草稿（已插入列表）。归档会话不主动作为当前会话
     * （与 deleteConversation 同规）——归档区不在抽屉主列表里，停在归档会话上继续问答，
     * 内容会落在用户找不到的会话里。
     */
    private fun ensureCurrentConversationVisible() {
        val current = conversations.firstOrNull { it.id == currentConversationId }
        if (current != null && !current.archived) return
        currentConversationId = conversations.firstOrNull { !it.archived }?.id
            ?: newConversationInternal().id
    }

    /** 先记录目标会话，抽屉关到几乎看不见时才真正切换 */
    fun deferSelectConversation(conversation: AgentConversation, onCloseDrawer: () -> Unit) {
        if (conversation.id == currentConversationId) { onCloseDrawer(); return }
        pendingConversationId = conversation.id
        onCloseDrawer()
    }

    /** 抽屉开始关闭时调用（app 壳 closeDrawer 启动时）：新列表在遮挡下完成组合，露出即就位 */
    fun applyPendingConversation() {
        val id = pendingConversationId ?: return
        val conv = conversations.firstOrNull { it.id == id } ?: run { pendingConversationId = null; return }
        pendingConversationId = null
        selectConversation(conv)
    }

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
        pristineListStates.remove(conversation.id)
        draftVersions.remove(conversation.id)
        if (currentConversationId == conversation.id) {
            // 空列表兜底：优先回退到最近的未归档会话（归档会话不主动作为当前会话）
            ensureCurrentConversationVisible()
        }
        schedulePersist()
    }

    fun renameConversation(conversation: AgentConversation, newTitle: String) {
        conversation.title = newTitle.trim().ifEmpty { "新对话" }
        // 手动命名后标题归用户所有：首题/演化都不再覆盖（CherryStudio 同款保护）
        conversation.titleIsUserDefined = true
        schedulePersist()
    }

    private fun newConversationInternal(): AgentConversation {
        val conversation = AgentConversation(java.util.UUID.randomUUID().toString(), "新对话")
        // 与 newConversation 一致按活跃序置顶（抽屉里新对话总在最上面）
        conversations.bumpByActivity(conversation, conversation.lastActiveAt)
        return conversation
    }
}
