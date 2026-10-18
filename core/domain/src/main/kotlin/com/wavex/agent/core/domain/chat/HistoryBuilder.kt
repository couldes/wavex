package com.wavex.agent.core.domain.chat

import com.wavex.agent.core.domain.model.ChatMessage
import com.wavex.agent.core.domain.model.ChatRequestMessage

/** 历史构建产物：请求历史 + 不可读附件名 + 各类内容存在标记（供降级链决策）。 */
class HistoryResult(
    val history: List<ChatRequestMessage>,
    /** 第一个无法读取的附件名（软件能收的格式挑选时不拦，能到这里只剩文件失效） */
    val unreadableName: String?,
    val historyHasAudio: Boolean,
    val historyHasImage: Boolean,
    val historyHasPdf: Boolean
) {
    /** 音频/图片/PDF 降级版历史：分别去掉对应附件后重发（其余内容保留）。lazy：无对应附件的请求永不构建 */
    val noAudioHistory: List<ChatRequestMessage> by lazy { dropAudio(history) }
    val noImageHistory: List<ChatRequestMessage> by lazy { dropImage(history) }
    val noPdfHistory: List<ChatRequestMessage> by lazy { dropPdf(history) }

    /** D-03 修复：累计剔除——重发时基于【上一轮已发送的历史】调用，而非从原始历史重建。
     *  判定式与三个 lazy 变体及 historyHasXxx 的类型定义同口径（勿单边改动）。
     *  三个变体都必须原样带上 toolCalls/toolCallId：丢了它会让降级重发时
     *  历史里只剩无主的 tool 结果，上游直接 400。 */
    fun dropAudio(hs: List<ChatRequestMessage>): List<ChatRequestMessage> = hs.map { m ->
        m.copy(imageDataUrls = m.imageDataUrls.filterNot { it.startsWith("x-audio:") })
    }

    fun dropImage(hs: List<ChatRequestMessage>): List<ChatRequestMessage> = hs.map { m ->
        // 只摘图片、保留音频与 PDF：判定式必须与 historyHasImage 的「图片」定义同口径。
        //（旧写法 filterNot { !startsWith("x-audio:") } 是「只留音频」——模型拒收图片
        // 触发降级时会话里的 PDF 被连带静默丢掉，用户内容白白损失）
        m.copy(
            imageDataUrls = m.imageDataUrls.filterNot {
                !it.startsWith("x-audio:") && !it.startsWith("x-pdf:")
            }
        )
    }

    fun dropPdf(hs: List<ChatRequestMessage>): List<ChatRequestMessage> = hs.map { m ->
        m.copy(imageDataUrls = m.imageDataUrls.filterNot { it.startsWith("x-pdf:") })
    }

    /** 附件占位文字（去附件后历史可能变空消息，替换为可读说明） */
    fun withFallbackNote(hs: List<ChatRequestMessage>): List<ChatRequestMessage> = hs.map { m ->
        if (m.role == "user" && m.text.isBlank() && m.imageDataUrls.isEmpty())
            m.copy(text = "（此条消息附带了本模型不支持的附件，已忽略附件内容）")
        else m
    }
}

/**
 * 请求历史构建：逐条解析消息，每条用户消息独立加载附件（图片→视觉消息、音频→input_audio、
 * 文本→内联）。之前只给最后一条用户消息附图，多轮发图时早前的图片会丢失。
 * 通过 ContentLoader 抽象附件加载，测试用假实现驱动；
 * 生产实现由调用方注入（app 组合根装配 data.DefaultAttachmentLoader）。
 */
object HistoryBuilder {

    /** 成功态文件占位：[附件 名字.ext]，与 ResponseImageExtractor.placeholder 的成功态同形（勿单边改动） */
    private val FILE_PLACEHOLDER = Regex("\\[附件 ([^\\]]+)\\]")

    suspend fun build(
        messages: List<ChatMessage>,
        loader: ContentLoader
    ): HistoryResult {
        val history = mutableListOf<ChatRequestMessage>()
        var unreadable: String? = null
        // 工具调用的配对簿：assistant 声明的 tool_use id → 它在 history 里的插入位置。
        // 位置而不是布尔值：补合成结果时必须插回声明处（见下方配对收尾），
        // 追加到末尾会让 tool 结果排在后续用户消息之后，上游直接 400
        val declaredAt = LinkedHashMap<String, Int>()
        val answeredToolIds = LinkedHashSet<String>()
        for (m in messages) {
            if (m.isError) continue
            // 纯工具消息正文可以为空，不能被「空消息跳过」规则吃掉
            if (m.text.isBlank() && m.attachments.isEmpty() &&
                m.toolCalls.isEmpty() && m.toolCallId == null
            ) continue
            // 工具结果消息：只留配对得上的（孤儿结果会被上游 400 拒绝）
            if (m.toolCallId != null) {
                if (m.toolCallId in declaredAt) {
                    answeredToolIds.add(m.toolCallId)
                    history.add(ChatRequestMessage("tool", m.text, toolCallId = m.toolCallId))
                }
                continue
            }
            if (m.fromUser && m.attachments.isNotEmpty()) {
                val images = mutableListOf<String>()
                // 音频全部保留（OpenAI input_audio 支持多条）：旧写法只留第一条，
                // 第二条起静默丢失，用户无从感知
                val audios = mutableListOf<String>()
                var text = m.text
                m.attachments.forEach { attachment ->
                    when (val loaded = loader.load(attachment)) {
                        null -> if (unreadable == null) unreadable = attachment.name
                        else -> when (loaded.first) {
                            "image" -> images.add(loaded.second)
                            "audio" -> audios.add("x-audio:${loaded.second}")
                            // PDF 原生 base64 上传（x-pdf: 前缀），模型层不支持时降级链处理
                            "pdf" -> images.add("x-pdf:${loaded.second}")
                            "text" -> text += (if (text.isBlank()) "" else "\n") + loaded.second
                        }
                    }
                }
                // 只发音频不带文字时部分模型会无视音频直接空谈：
                // 自动补一句简短指令，明确告知「这是一段音频」
                if (audios.isNotEmpty() && text.isBlank()) {
                    text = "（这段对话附带了一段音频，请先听取它的内容再回答）"
                }
                history.add(ChatRequestMessage("user", text, images + audios))
            } else {
                // 成功态占位已从正文删除：纯附件助手消息（正文空）历史补一句说明——
                // 空 assistant content 部分提供方拒收，模型也需知道自己生成过附件
                var text = m.text
                // 提取占位符会原样进入模型历史——模型会把裸的「[图片]」「[附件 x]」
                // 当作“发附件的方式”照抄（实测：再次索图时只复读占位符，不再输出真
                // markdown 图）。改写为自描述说明打破模仿环；失败/中断类占位
                // （保存失败/过大/传输中断）本身自描述，不处理
                if (!m.fromUser) {
                    text = text.replace("[图片]", "（图片已作为附件显示给用户）")
                        .replace(FILE_PLACEHOLDER) { "（附件 ${it.groupValues[1]} 已显示给用户）" }
                }
                if (!m.fromUser && text.isBlank() && m.attachments.isNotEmpty()) {
                    text = "（本条回复生成了附件）"
                }
                if (!m.fromUser && m.toolCalls.isNotEmpty()) {
                    // 记下声明位置 = 本条 assistant 消息即将占用的下标
                    m.toolCalls.forEach { declaredAt[it.id] = history.size }
                }
                history.add(
                    ChatRequestMessage(
                        role = if (m.fromUser) "user" else "assistant",
                        text = text,
                        toolCalls = m.toolCalls,
                        // 思考只在消息真的带工具调用时回传：Anthropic 只在这条链路上要求
                        // thinking 配对，普通历史硬塞思考块只会白烧 token
                        thinking = if (m.toolCalls.isNotEmpty()) m.reasoning.ifBlank { null } else null,
                        thinkingSignature = if (m.toolCalls.isNotEmpty())
                            m.thinkingSignature.ifBlank { null } else null,
                    )
                )
            }
        }
        // 配对收尾：有 tool_use 却没有结果时补一条合成结果。
        // 两种来源：搜索途中 App 被杀（结果没落盘），以及常态——工具结果只活在
        // ToolLoop 的临时历史里，落盘的消息树只有 assistant(toolCalls)+正文。
        // 孤儿 tool_use 会被 OpenAI 与 Anthropic 双方 400 拒绝，所以必须补；
        // 且必须紧跟声明它的 assistant 消息（协议要求），不能追加到末尾。
        // 按声明位置倒序插入，避免前面的插入把后面的下标推移。
        declaredAt.entries
            .filterNot { it.key in answeredToolIds }
            .sortedByDescending { it.value }
            .forEach { (id, at) ->
                history.add(
                    at + 1,
                    ChatRequestMessage(
                        role = "tool",
                        text = """{"error":"工具执行被中断，没有结果","retryable":true}""",
                        toolCallId = id,
                    )
                )
            }
        return HistoryResult(
            history = history,
            unreadableName = unreadable,
            historyHasAudio = history.any { m -> m.imageDataUrls.any { it.startsWith("x-audio:") } },
            historyHasImage = history.any { m -> m.imageDataUrls.any { !it.startsWith("x-audio:") && !it.startsWith("x-pdf:") } },
            historyHasPdf = history.any { m -> m.imageDataUrls.any { it.startsWith("x-pdf:") } }
        )
    }
}
