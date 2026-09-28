package com.wavex.agent.engine

import android.content.Context
import com.wavex.agent.data.AttachmentLoader
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.model.ChatRequestMessage

/** 历史构建产物：请求历史 + 不可读附件名 + 各类内容存在标记（供降级链决策）。 */
internal class HistoryResult(
    val history: List<ChatRequestMessage>,
    /** 第一个无法读取的附件名（软件能收的格式挑选时不拦，能到这里只剩文件失效） */
    val unreadableName: String?,
    val historyHasAudio: Boolean,
    val historyHasImage: Boolean,
    val historyHasPdf: Boolean
) {
    /** 音频/图片/PDF 降级版历史：分别去掉对应附件后重发（其余内容保留）。lazy：无对应附件的请求永不构建 */
    val noAudioHistory: List<ChatRequestMessage> by lazy {
        history.map { m ->
            ChatRequestMessage(m.role, m.text, m.imageDataUrls.filterNot { it.startsWith("x-audio:") })
        }
    }
    val noImageHistory: List<ChatRequestMessage> by lazy {
        history.map { m ->
            ChatRequestMessage(m.role, m.text, m.imageDataUrls.filterNot { !it.startsWith("x-audio:") })
        }
    }
    val noPdfHistory: List<ChatRequestMessage> by lazy {
        history.map { m ->
            ChatRequestMessage(m.role, m.text, m.imageDataUrls.filterNot { it.startsWith("x-pdf:") })
        }
    }

    /** 附件占位文字（去附件后历史可能变空消息，替换为可读说明） */
    fun withFallbackNote(hs: List<ChatRequestMessage>): List<ChatRequestMessage> = hs.map { m ->
        if (m.role == "user" && m.text.isBlank() && m.imageDataUrls.isEmpty())
            ChatRequestMessage("user", "（此条消息附带了本模型不支持的附件，已忽略附件内容）")
        else m
    }
}

/**
 * 请求历史构建：逐条解析消息，每条用户消息独立加载附件（图片→视觉消息、音频→input_audio、
 * 文本→内联）。之前只给最后一条用户消息附图，多轮发图时早前的图片会丢失。
 * 通过 ContentLoader 抽象附件加载，测试用假实现驱动。
 */
internal object HistoryBuilder {

    /** 生产环境默认加载器：走 AttachmentLoader（压缩/转 base64/缓存）；Context 在此闭包，不进引擎 */
    fun systemLoader(context: Context): ContentLoader = ContentLoader { attachment ->
        AttachmentLoader.loadContent(context, attachment)
    }

    suspend fun build(
        messages: List<ChatMessage>,
        loader: ContentLoader
    ): HistoryResult {
        val history = mutableListOf<ChatRequestMessage>()
        var unreadable: String? = null
        for (m in messages) {
            if (m.isError) continue
            if (m.text.isBlank() && m.attachments.isEmpty()) continue
            if (m.fromUser && m.attachments.isNotEmpty()) {
                val images = mutableListOf<String>()
                var audioUrl: String? = null
                var text = m.text
                m.attachments.forEach { attachment ->
                    when (val loaded = loader.load(attachment)) {
                        null -> if (unreadable == null) unreadable = attachment.name
                        else -> when (loaded.first) {
                            "image" -> images.add(loaded.second)
                            "audio" -> if (audioUrl == null) audioUrl = "x-audio:${loaded.second}"
                            // PDF 原生 base64 上传（x-pdf: 前缀），模型层不支持时降级链处理
                            "pdf" -> images.add("x-pdf:${loaded.second}")
                            "text" -> text += (if (text.isBlank()) "" else "\n") + loaded.second
                        }
                    }
                }
                // 只发音频不带文字时部分模型会无视音频直接空谈：
                // 自动补一句简短指令，明确告知「这是一段音频」
                if (audioUrl != null && text.isBlank()) {
                    text = "（这段对话附带了一段音频，请先听取它的内容再回答）"
                }
                history.add(ChatRequestMessage("user", text, images + listOfNotNull(audioUrl)))
            } else {
                history.add(ChatRequestMessage(if (m.fromUser) "user" else "assistant", m.text))
            }
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
