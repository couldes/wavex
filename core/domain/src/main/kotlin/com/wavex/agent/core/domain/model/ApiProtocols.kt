package com.wavex.agent.core.domain.model

/**
 * 按 Base URL 识别协议——用户不需要选，填地址即可。
 *
 * 纯字符串判断，因此放在 domain：network 层用它决定端点叶子与鉴权方言，
 * feature 层用它解释「当前模型对思考等级的支持形态」（[ApiProtocol] 是
 * [com.wavex.agent.core.domain.chat.reasoningSupportOf] 的入参）。
 * 以前这段逻辑埋在 network 的 internal 函数里，feature 拿不到，只能靠猜。
 */
object ApiProtocols {

    /**
     * - 域名以 anthropic.com 结尾，或路径中出现 /anthropic 段
     *   （如 DeepSeek 官方 Anthropic 网关 https://api.deepseek.com/anthropic）→ Anthropic；
     * - 其余一律按 OpenAI 兼容协议。
     */
    fun detect(baseUrl: String): ApiProtocol {
        val lower = canonicalBase(baseUrl).ifBlank { baseUrl.trim() }.lowercase()
        if (!lower.startsWith("http")) return ApiProtocol.OPENAI
        val noScheme = lower.substringAfter("://")
        val host = noScheme.substringBefore('/').removePrefix("www.")
        val path = noScheme.substringAfter('/', "")
        val isAnthropic = host.endsWith("anthropic.com") || path.split('/').any { it == "anthropic" }
        return if (isAnthropic) ApiProtocol.ANTHROPIC else ApiProtocol.OPENAI
    }

    /** 与 network 的 Connection.canonicalBase 同规则的归一化（只用于协议判定，不拼端点） */
    private fun canonicalBase(raw: String): String {
        val trimmed = raw.trim().trimEnd('/', '#')
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val afterScheme = withScheme.substringAfter("://")
        val host = afterScheme.substringBefore('/')
        if (host.isBlank()) return withScheme
        val origin = withScheme.substringBefore("://") + "://" + host
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

    private val leafSuffixes =
        listOf("/chat/completions", "/completions", "/messages", "/responses", "/models")
}
