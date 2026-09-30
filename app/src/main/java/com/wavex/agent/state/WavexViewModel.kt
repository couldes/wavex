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
import com.wavex.agent.engine.ChatApi
import com.wavex.agent.engine.HistoryBuilder
import com.wavex.agent.engine.FallbackAction
import com.wavex.agent.engine.FallbackFlags
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.model.StoredMessage
import com.wavex.agent.model.AgentConversationData
import com.wavex.agent.model.ConversationSnapshot
import com.wavex.agent.model.ChatRequestMessage
import com.wavex.agent.data.Provider
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
    /** 自动外部备份落点（应用外部目录，卸载后通常保留）；存储不可用时为 null */
    private val backupFile: java.io.File? = null
) : ViewModel() {
    var themeChoice by mutableStateOf(ThemeChoice.SYSTEM)
    var selectedTab by mutableStateOf(MainTab.CHAT)

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
                    conversation.updateMessageAt(placeholderIndex, ChatMessage(text = "（模型没有返回内容）", fromUser = false, isError = true))
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
                // 首轮问答完成后自动命名（ChatGPT/Gemini 式），失败回退为首条消息截断
                maybeAutoTitle(conversation)
            }
        }
        streamJobs[convId] = job
    }

    /**
     * 自动命名：只在标题还是「新对话」时执行（手动改名后不覆盖），
     * 用当前模型生成 ≤14 字标题；调用失败回退为首条用户消息前 28 字。
     */
    private fun maybeAutoTitle(conversation: AgentConversation) {
        if (conversation.title != "新对话") return
        val firstUser = conversation.messages.firstOrNull { it.fromUser && !it.isError } ?: return
        if (firstUser.text.isBlank() && firstUser.attachments.isEmpty()) return
        val provider = currentProvider ?: return
        val firstAssistant = conversation.messages.firstOrNull { !it.fromUser && !it.isError }?.text ?: ""
        engineScope.launch {
            val generated = ApiClient.generateTitle(provider, firstUser.text, firstAssistant)
            conversation.title = generated
                ?: firstUser.text.take(28).ifBlank {
                    // 纯附件消息：用附件名生成标题（拍照附件用友好名）
                    firstUser.attachments.firstOrNull()?.let { attachmentDisplayName(it.name).take(20) } ?: "新对话"
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
        providers.addAll(providerStore.loadProviders())
        currentProviderId = providerStore.loadCurrentProviderId()?.takeIf { saved ->
            providers.any { it.id == saved }
        }
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
        // 重装自动找回：主文件不存在（新装/清数据）而外部备份存在时，先恢复再加载
        backupFile?.let { conversationStore?.maybeRestoreFromBackup(it) }
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
                nodes = nodes,
                children = c.children.filterValues { it.isNotEmpty() }.mapValues { (_, list) -> list.map { it.id } },
                activeChild = c.activeChild.toMap()
            )
        }

    /** 最近一次自动备份时间（epoch 毫秒）；从未备份/无备份文件时为 null。设置页展示用 */
    fun lastBackupAt(): Long? =
        backupFile?.takeIf { it.exists() && it.length() > 0 }?.lastModified()

    // ---- 自动外部备份：写盘成功后节流复制到应用外部目录（卸载后可找回） ----

    private var lastAutoBackupAt = 0L

    private suspend fun autoBackupIfDue() {
        val target = backupFile ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastAutoBackupAt != 0L && now - lastAutoBackupAt < AUTO_BACKUP_INTERVAL_MS) return
        lastAutoBackupAt = now
        withContext(Dispatchers.IO) { conversationStore?.backupTo(target) }
    }

    /**
     * 导出全部对话到 SAF uri（设置页「导出全部对话」）：与持久化同一 JSON 格式，
     * 可直接被导入。快照在调用线程（读 Compose 状态），写盘在 IO。返回是否成功。
     */
    suspend fun exportConversations(context: android.content.Context, uri: android.net.Uri): Boolean {
        val json = conversationStore?.serialize(snapshotData()) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } != null
            } catch (_: Exception) {
                false
            }
        }
    }

    /** 预读导入文件，返回其中的对话数（0 = 无效）；用于导入前确认弹窗展示数目 */
    suspend fun peekImportCount(context: android.content.Context, uri: android.net.Uri): Int {
        val raw = readUriText(context, uri) ?: return 0
        return conversationStore?.parse(raw)?.size ?: 0
    }

    private suspend fun readUriText(context: android.content.Context, uri: android.net.Uri): String? = try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 从 SAF uri 导入对话：整体替换当前列表（含停止所有进行中的生成）。
     * 返回导入的对话数；0 = 文件无法读取或解析不出对话。
     */
    suspend fun importConversations(context: android.content.Context, uri: android.net.Uri): Int {
        val raw = readUriText(context, uri) ?: return 0
        val snapshots = conversationStore?.parse(raw) ?: return 0
        if (snapshots.isEmpty()) return 0
        // 替换前停掉所有生成：旧会话对象即将弃用，协程继续往里写只是白写
        synchronized(streamJobs) { streamJobs.keys.toList() }.forEach { stopGeneration(it) }
        applySnapshots(snapshots)
        lastAutoBackupAt = 0L // 导入后的数据立刻备份一份
        schedulePersist()
        return snapshots.size
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
    /** 抽屉关闭后调用：真正应用切换 */
    fun applyPendingConversation() {
        val id = pendingConversationId ?: return
        val conv = conversations.firstOrNull { it.id == id } ?: run { pendingConversationId = null; return }
        pendingConversationId = null
        selectConversation(conv)
    }
    /** 新建对话也走「先关抽屉后切内容」：与选会话体验一致 */
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
                    container.conversationBackupFile
                )
            }
        }
    }
}

