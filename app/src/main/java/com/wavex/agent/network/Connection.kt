package com.wavex.agent.network

import com.wavex.agent.model.ApiProtocol
import okhttp3.Request

/**
 * 连接体检结果：把 App 替用户推断出来的东西原样交给 UI 显示。
 * 只填 Base URL + API Key 意味着大量决定由程序代做，代做的结果必须可见，
 * 否则连不上时用户完全无从下手。
 */
internal data class ConnectionReport(
    val ok: Boolean,
    val protocol: ApiProtocol,
    val endpoint: String,
    val dialect: AuthDialect,
    val modelCount: Int?,
    val latencyMs: Long,
    val error: String?
) {
    /** 第一行结论，第二行推断详情（最终地址 / 协议 / 实际生效的鉴权头 / 耗时）。 */
    fun summaryLines(): List<String> {
        val head = if (ok) {
            if (modelCount != null && modelCount > 0) "连接成功，$modelCount 个模型可用"
            else "连接成功（未开放模型列表，可手动输入模型名）"
        } else {
            "连接失败：${error ?: "未知错误"}"
        }
        val detail = "${Connection.protocolLabel(protocol)} · ${Connection.dialectLabel(dialect)}" +
            " · ${latencyMs}ms\n$endpoint"
        return listOf(head, detail)
    }
}

/**
 * 密钥头方言。同一个协议在不同网关上要求的鉴权头不一样：官方 Anthropic 只认
 * x-api-key，而多数 Claude 兼容中转站其实吃 Authorization: Bearer。
 */
internal enum class AuthDialect { BEARER, X_API_KEY }

/**
 * Base URL 归一化、端点拼接与鉴权头构造。全部是纯函数，单独放一个文件是为了能在
 * JVM 单测里覆盖：用户粘贴的地址形态不可控（完整端点、缺协议头、尾斜杠、带版本段），
 * 这里拼错一个斜杠就是「连不上」，而没有测试的话根本无从定位。
 */
internal object Connection {

    /**
     * 用户经常直接从接口文档里复制完整地址（…/v1/chat/completions）当 Base URL，
     * 不剥掉叶子再拼就会得到 …/chat/completions/chat/completions。
     */
    private val leafSuffixes =
        listOf("/chat/completions", "/completions", "/messages", "/responses", "/models")

    /** /v1、/v4、/v1beta、/v1.5 这类版本段结尾；结尾已带版本段就不再补 /v1 */
    private val versionTail = Regex("/v\\d+(\\.\\d+)?[a-z]*$", RegexOption.IGNORE_CASE)

    /** 归一化成「前缀」：补协议头、去尾斜杠与 query/fragment、剥掉接口叶子。 */
    fun canonicalBase(raw: String): String {
        val trimmed = raw.trim().trimEnd('/', '#')
        if (trimmed.isEmpty()) return ""
        // 只填域名/路径不填协议头是高频输入，默认按 https 处理
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val afterScheme = withScheme.substringAfter("://")
        val host = afterScheme.substringBefore('/')
        if (host.isBlank()) return withScheme
        val origin = withScheme.substringBefore("://") + "://" + host
        // substringAfter('/') 会吃掉分隔符，路径要自己补回开头的 /
        var path = afterScheme.substringAfter('/', "").substringBefore('#').substringBefore('?')
        if (path.isNotEmpty()) path = "/$path"
        path = path.trimEnd('/')
        var stripped = true
        while (stripped) {
            stripped = false
            for (leaf in leafSuffixes) {
                if (path.endsWith(leaf, ignoreCase = true)) {
                    path = path.removeSuffix(leaf).trimEnd('/')
                    stripped = true
                }
            }
        }
        return origin + path
    }

    fun chatEndpoint(baseUrl: String, protocol: ApiProtocol): String =
        endpoint(baseUrl, protocol, if (protocol == ApiProtocol.ANTHROPIC) "/messages" else "/chat/completions")

    fun modelsEndpoint(baseUrl: String, protocol: ApiProtocol): String =
        endpoint(baseUrl, protocol, "/models")

    /** /models 请求候选（按序尝试）。 */
    internal data class ModelsCandidate(val url: String, val protocol: ApiProtocol)

    /**
     * /models 候选列表（404/405 换下一个，cc-switch 同款策略）：
     * 1. 主端点 {base}/v1/models（版本段结尾则 {base}/models）；
     * 2. base 以已知 Anthropic 兼容子路径结尾时（如 DeepSeek 官方 …/anthropic），剥掉子路径
     *    后在根域名上试 OpenAI 格式的 /v1/models 与 /models——兼容层普遍不挂 /models，
     *    而根域名端点用同一个 key 即可拿到真实列表。
     */
    fun modelsCandidates(baseUrl: String, protocol: ApiProtocol): List<ModelsCandidate> {
        val base = canonicalBase(baseUrl)
        if (base.isEmpty()) return emptyList()
        val candidates = mutableListOf(ModelsCandidate(modelsEndpoint(baseUrl, protocol), protocol))
        compatRoot(base)?.let { root ->
            candidates.add(ModelsCandidate("$root/v1/models", ApiProtocol.OPENAI))
            candidates.add(ModelsCandidate("$root/models", ApiProtocol.OPENAI))
        }
        return candidates
    }

    /** 已知的「Anthropic 协议兼容子路径」后缀；最长优先，与 cc-switch 名单一致。 */
    private val anthropicCompatSuffixes = listOf(
        "/api/claudecode", "/api/anthropic", "/apps/anthropic",
        "/api/coding", "/claudecode", "/anthropic", "/step_plan", "/coding", "/claude"
    )

    /** base 以已知兼容子路径结尾时返回剥掉后缀的根（如 …/anthropic → 站点根），否则 null。 */
    private fun compatRoot(base: String): String? {
        val path = base.substringAfter("://").substringAfter('/', "")
        if (path.isEmpty()) return null
        val lower = "/$path".lowercase()
        val suffix = anthropicCompatSuffixes.firstOrNull { lower.endsWith(it) } ?: return null
        // suffix 带前导 /，takeLast 去掉它拿到纯路径段，原样（保留大小写）从 base 里剥掉
        return base.removeSuffix(path.takeLast(suffix.length - 1)).trimEnd('/')
    }

    private fun endpoint(baseUrl: String, protocol: ApiProtocol, leaf: String): String {
        val base = canonicalBase(baseUrl)
        if (base.isEmpty()) return ""
        val path = base.substringAfter("://").substringAfter('/', "")
        val hasVersion = path.isNotEmpty() && versionTail.containsMatchIn("/$path")
        return if (hasVersion) "$base$leaf" else "$base/v1$leaf"
    }

    fun defaultDialect(protocol: ApiProtocol): AuthDialect =
        if (protocol == ApiProtocol.ANTHROPIC) AuthDialect.X_API_KEY else AuthDialect.BEARER

    fun otherDialect(dialect: AuthDialect): AuthDialect =
        if (dialect == AuthDialect.BEARER) AuthDialect.X_API_KEY else AuthDialect.BEARER

    /**
     * Anthropic 协议即使改用 Bearer 也必须带 anthropic-version——官方网关缺它会报
     * 400 invalid_request_error，与鉴权方式无关。
     */
    fun applyAuth(
        builder: Request.Builder,
        apiKey: String,
        protocol: ApiProtocol,
        dialect: AuthDialect
    ): Request.Builder {
        val withKey = when (dialect) {
            AuthDialect.BEARER -> builder.header("Authorization", "Bearer $apiKey")
            AuthDialect.X_API_KEY -> builder.header("x-api-key", apiKey)
        }
        return if (protocol == ApiProtocol.ANTHROPIC) {
            withKey.header("anthropic-version", "2023-06-01")
        } else withKey
    }

    fun protocolLabel(protocol: ApiProtocol): String =
        if (protocol == ApiProtocol.ANTHROPIC) "Anthropic" else "OpenAI 兼容"

    fun dialectLabel(dialect: AuthDialect): String =
        if (dialect == AuthDialect.BEARER) "Bearer" else "x-api-key"
}
