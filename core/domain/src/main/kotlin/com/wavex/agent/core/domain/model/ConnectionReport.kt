package com.wavex.agent.core.domain.model

/**
 * 连接体检结果：把 App 替用户推断出来的东西原样交给 UI 显示。
 * 只填 Base URL + API Key 意味着大量决定由程序代做，代做的结果必须可见，
 * 否则连不上时用户完全无从下手。
 */
data class ConnectionReport(
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
        val detail = "${protocolLabel(protocol)} · ${dialectLabel(dialect)}" +
            " · ${latencyMs}ms\n$endpoint"
        return listOf(head, detail)
    }
}

/**
 * 密钥头方言。同一个协议在不同网关上要求的鉴权头不一样：官方 Anthropic 只认
 * x-api-key，而多数 Claude 兼容中转站其实吃 Authorization: Bearer。
 */
enum class AuthDialect { BEARER, X_API_KEY }

/** 协议显示名（原 network.Connection.protocolLabel：随 ConnectionReport 移入 domain，标签逻辑不依赖网络层） */
fun protocolLabel(protocol: ApiProtocol): String =
    if (protocol == ApiProtocol.ANTHROPIC) "Anthropic" else "OpenAI 兼容"

/** 鉴权方言显示名（原 network.Connection.dialectLabel） */
fun dialectLabel(dialect: AuthDialect): String =
    if (dialect == AuthDialect.BEARER) "Bearer" else "x-api-key"
