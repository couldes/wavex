package com.wavex.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.wavex.agent.data.Provider
import com.wavex.agent.data.ProviderStore
import com.wavex.agent.model.ApiProtocol
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.TREE_ROOT
import com.wavex.agent.model.ChatRequestMessage
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.network.ApiClient
import com.wavex.agent.network.formatApiError
import com.wavex.agent.network.detectProtocol

/**
 * 对话持久化：JSON 文件存 filesDir/conversations.json（分叉树格式）。
 * 每个对话存：nodes（全部消息节点，含各分支）+ children（父 id -> 子 id 列表）+
 * activeChild（父 id -> 当前激活子 id）；旧版平铺 messages 格式自动迁移为线性树。
 * 附件 URI 之前已 takePersistableUriPermission，重启后仍可读。
 * 保存时机：消息列表变更时防抖写入，避免流式输出每帧写盘。
 */
class ConversationStore(context: Context) {
    private val file = java.io.File(context.filesDir, "conversations.json")

    private fun parseStoredMessage(m: JSONObject): StoredMessage {
        val attsJson = m.optJSONArray("attachments") ?: JSONArray()
        val atts = mutableListOf<Pair<String, String>>()
        for (k in 0 until attsJson.length()) {
            val a = attsJson.getJSONObject(k)
            atts.add(a.optString("uri") to a.optString("name"))
        }
        return StoredMessage(
            id = m.optString("id", ""),
            text = m.optString("text"),
            fromUser = m.optBoolean("fromUser"),
            isError = m.optBoolean("isError"),
            reasoning = m.optString("reasoning"),
            attachments = atts
        )
    }

    /** 旧格式（平铺消息列表）→ 线性树迁移 */
    private fun linearToTree(msgs: List<StoredMessage>): TreeData {
        val nodes = LinkedHashMap<String, StoredMessage>()
        val children = LinkedHashMap<String, MutableList<String>>()
        val activeChild = LinkedHashMap<String, String>()
        var parent = TREE_ROOT
        msgs.forEach { m ->
            nodes[m.id] = m
            children.getOrPut(parent) { mutableListOf() }.add(m.id)
            activeChild[parent] = m.id
            parent = m.id
        }
        return TreeData(nodes, children, activeChild)
    }

    /** 加载全部对话（含旧格式自动迁移） */
    fun load(): List<ConversationSnapshot> {
        val result = mutableListOf<ConversationSnapshot>()
        val raw = try { file.readText() } catch (_: Exception) { null } ?: return result
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val conv = arr.getJSONObject(i)
                if (conv.has("nodes")) {
                    // 新格式：分叉树
                    val nodesJson = conv.getJSONArray("nodes")
                    val nodes = LinkedHashMap<String, StoredMessage>()
                    for (j in 0 until nodesJson.length()) {
                        var m = parseStoredMessage(nodesJson.getJSONObject(j))
                        if (m.id.isBlank()) m = m.copy(id = java.util.UUID.randomUUID().toString())
                        nodes[m.id] = m
                    }
                    val children = LinkedHashMap<String, List<String>>()
                    val childrenJson = conv.optJSONObject("children") ?: JSONObject()
                    val it = childrenJson.keys()
                    while (it.hasNext()) {
                        val parent = it.next()
                        val ids = mutableListOf<String>()
                        val arr2 = childrenJson.optJSONArray(parent) ?: JSONArray()
                        for (k in 0 until arr2.length()) ids.add(arr2.optString(k))
                        children[parent] = ids
                    }
                    val activeChild = LinkedHashMap<String, String>()
                    val activeJson = conv.optJSONObject("activeChild") ?: JSONObject()
                    val it2 = activeJson.keys()
                    while (it2.hasNext()) {
                        val parent = it2.next()
                        activeChild[parent] = activeJson.optString(parent)
                    }
                    result.add(ConversationSnapshot(conv.optString("id"), conv.optString("title", "新对话"), TreeData(nodes, children, activeChild)))
                } else {
                    // 旧格式：平铺消息 → 迁移成线性树
                    val msgsJson = conv.optJSONArray("messages") ?: JSONArray()
                    val msgs = mutableListOf<StoredMessage>()
                    for (j in 0 until msgsJson.length()) {
                        var m = parseStoredMessage(msgsJson.getJSONObject(j))
                        if (m.id.isBlank()) m = m.copy(id = java.util.UUID.randomUUID().toString())
                        msgs.add(m)
                    }
                    result.add(ConversationSnapshot(conv.optString("id"), conv.optString("title", "新对话"), linearToTree(msgs)))
                }
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(conversations: List<AgentConversationData>) {
        try {
            val arr = JSONArray()
            conversations.forEach { conv ->
                val nodesJson = JSONArray()
                conv.nodes.values.forEach { m ->
                    val mJson = JSONObject()
                        .put("id", m.id)
                        .put("text", m.text)
                        .put("fromUser", m.fromUser)
                        .put("isError", m.isError)
                    if (m.reasoning.isNotBlank()) mJson.put("reasoning", m.reasoning)
                    if (m.attachments.isNotEmpty()) {
                        val atts = JSONArray()
                        m.attachments.forEach { a ->
                            atts.put(JSONObject().put("uri", a.first).put("name", a.second))
                        }
                        mJson.put("attachments", atts)
                    }
                    nodesJson.put(mJson)
                }
                val childrenJson = JSONObject()
                conv.children.forEach { (parent, ids) -> childrenJson.put(parent, JSONArray(ids)) }
                val activeJson = JSONObject()
                conv.activeChild.forEach { (parent, id) -> activeJson.put(parent, id) }
                arr.put(
                    JSONObject()
                        .put("id", conv.id)
                        .put("title", conv.title)
                        .put("nodes", nodesJson)
                        .put("children", childrenJson)
                        .put("activeChild", activeJson)
                )
            }
            // 原子写入：先写临时文件再改名，进程在写盘中途被杀不会损坏 conversations.json
            // （旧实现直接 writeText，写一半被杀 = 全部历史丢失）
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                // 个别文件系统 rename 到已存在目标会失败：删旧文件后重试一次
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: Exception) {
            // 磁盘满等异常时静默失败，不打断聊天
        }
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

/** 附件内容加载：图片转 base64 data URL（视觉消息），文本文件内联内容，PDF 转逐页图片；其余不支持。 */
object AttachmentLoader {
    private const val MAX_TEXT_CHARS = 20000
    // 图片发送上限：长边像素与压缩质量。超大图直接 base64 会 OOM（实测 60MB 图闪退），
    // 先降采样到视觉模型识别够用的尺寸再压缩，兼顾清晰度与请求体大小。
    private const val MAX_IMAGE_DIMEN = 1568
    private const val JPEG_QUALITY = 85
    // 音频发送上限：base64 后约 20MB 请求体，超出直接拒绝（中转站一般也会拒）
    private const val MAX_AUDIO_BYTES = 15 * 1024 * 1024
    // PDF 发送上限：同量级防请求体爆炸；PDF 原生 base64 上传（OpenAI "file" 类型），
    // 模型直接读原始文档（保留排版/文本层），而非转图片
    // OpenAI 兼容接口支持的音频格式（input_audio）；其余音频格式暂不支持
    private val AUDIO_MIMES = mapOf(
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav", "audio/vnd.wave" to "wav",
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3"
    )
    // 已解析附件的内存缓存（key=uri），多轮对话不再重复解码/转 base64，减少 GC 卡顿
    private val cache = object : android.util.LruCache<String, Pair<String, String>>(6_000_000) {
        override fun sizeOf(key: String, value: Pair<String, String>): Int = value.second.length
    }
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "yml", "yaml", "log",
        "kt", "java", "py", "js", "ts", "jsx", "tsx", "html", "htm", "css", "scss",
        "c", "h", "cpp", "hpp", "cc", "sh", "bat", "ps1", "sql", "toml", "ini", "conf", "cfg",
        "gradle", "properties", "env", "gitignore", "dockerfile", "cmake", "swift", "rb", "go",
        "rs", "php", "dart", "lua", "pl", "r", "m", "srt", "vtt", "lrc"
    )
    // 按扩展名识别的音频格式（MIME 缺失时的兑底）
    private val AUDIO_EXTENSIONS = mapOf(
        "mp3" to "mp3", "wav" to "wav", "wave" to "wav"
    )
    // 按扩展名识别的图片格式
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    /**
     * 软件层面能否处理该附件：返回 null = 支持；非 null = 不支持原因（可直接展示给用户）。
     * 软件无法处理的格式（PDF/未知类型/非 MP3·WAV 音频）转不成 API 载荷，
     * 模型肯定收不到 —— 挑选时即拦截并报错，不浪费时间上传。
     * 软件支持的格式（图片/音频/文本）放行，模型层面是否接受由发送后的
     * 降级链判断（isModelError），两层错误分开、各自在最早时机提示。
     */
    fun unsupportedReason(context: android.content.Context, uri: android.net.Uri, name: String): String? {
        val mime = (try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "").lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        val effectiveMime = when {
            mime.isNotBlank() && mime != "application/octet-stream" -> mime
            ext in IMAGE_EXTENSIONS -> "image/x"
            ext in AUDIO_EXTENSIONS -> "audio/x"
            else -> mime
        }
        return when {
            effectiveMime == "application/pdf" || ext == "pdf" -> null
            effectiveMime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> null
            effectiveMime.startsWith("text/") || ext in TEXT_EXTENSIONS -> null
            else -> {
                val format = AUDIO_MIMES[effectiveMime]
                    ?: AUDIO_EXTENSIONS[ext]
                    ?: if (effectiveMime.startsWith("audio/")) effectiveMime.substringAfter('/') else null
                when {
                    format == null -> "暂不支持该文件类型（目前支持图片、音频和文本类文件）"
                    format != "mp3" && format != "wav" -> "音频仅支持 MP3 / WAV 格式"
                    else -> null
                }
            }
        }
    }

    /**
     * 图片 → 缩放后的 JPEG data URL：
     * 1) 只读尺寸（inJustDecodeBounds），按 2 的幂降采样到长边 ≤ MAX_IMAGE_DIMEN；
     * 2) 再精确缩放到目标尺寸，JPEG 压缩后 base64。
     * 这样任何分辨率的图都能稳定控制在几百 KB 内，不会 OOM。
     */
    private fun loadScaledImage(resolver: android.content.ContentResolver, uri: android.net.Uri): String? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_IMAGE_DIMEN) sample *= 2
        val sampled = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, sampled)
        } ?: return null

        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge > MAX_IMAGE_DIMEN) {
            val scale = MAX_IMAGE_DIMEN.toFloat() / longEdge
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        if (scaled !== bitmap) bitmap.recycle()

        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        if (scaled.width > 0) scaled.recycle()
        return "data:image/jpeg;base64,$b64"
    }

    /** 返回 (类型, 内容)：image=dataURL、text=内联文本；null=不支持该类型。结果会进 LruCache。 */
    suspend internal fun loadContent(context: android.content.Context, attachment: ChatAttachment): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val cacheKey = attachment.uri.toString()
            cache.get(cacheKey)?.let { return@withContext it }
            try {
                val uri = attachment.uri
                val resolver = context.contentResolver
                val mime = (resolver.getType(uri) ?: "").lowercase()
                val ext = attachment.name.substringAfterLast('.', "").lowercase()
                // MIME 缺失/笼统（application/octet-stream）时按扩展名兑底判断
                val effectiveMime = when {
                    mime.isNotBlank() && mime != "application/octet-stream" -> mime
                    ext in IMAGE_EXTENSIONS -> "image/x"
                    ext in AUDIO_EXTENSIONS -> "audio/x"
                    else -> mime
                }
                val loaded = when {
                    effectiveMime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> {
                        val dataUrl = loadScaledImage(resolver, uri) ?: return@withContext null
                        "image" to dataUrl
                    }
                    effectiveMime.startsWith("text/") || ext in TEXT_EXTENSIONS -> {
                        val text = resolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        } ?: return@withContext null
                        if (text.length > MAX_TEXT_CHARS) {
                            "text" to "文件 ${attachment.name}：\n${text.take(MAX_TEXT_CHARS)}\n…（内容过长已截断）"
                        } else {
                            "text" to "文件 ${attachment.name}：\n$text"
                        }
                    }
                    effectiveMime in AUDIO_MIMES || effectiveMime.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> {
                        val format = AUDIO_MIMES[effectiveMime]
                            ?: AUDIO_EXTENSIONS[ext]
                            ?: if (effectiveMime.startsWith("audio/")) effectiveMime.substringAfter('/') else null
                        // OpenAI input_audio 只收 mp3/wav，其余音频格式不发送（否则整个请求被拒）
                        if (format == null || format !in setOf("mp3", "wav")) return@withContext null
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@withContext null
                        if (bytes.size > MAX_AUDIO_BYTES) return@withContext null
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "audio" to "$format|$b64"
                    }
                    // PDF：API 协议原生支持（OpenAI file 类型 / Anthropic document 类型），
                    // 原样 base64 上传，模型直接读原始文档；模型不支持时由发送后降级链处理
                    effectiveMime == "application/pdf" || ext == "pdf" -> {
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@withContext null
                        if (bytes.size > MAX_AUDIO_BYTES) return@withContext null
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        "pdf" to b64
                    }
                    else -> null
                }
                loaded?.let { cache.put(cacheKey, it) }
                loaded
            } catch (_: Exception) {
                null
            }
        }
}
