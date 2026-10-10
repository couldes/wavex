package com.wavex.agent.core.network

import org.json.JSONObject

/**
 * 已知密钥打码（cc-switch redact_known_secrets_strict 同款）：错误文本要展示到
 * 聊天气泡/体检报告或落盘用量记录，网关报错里回显的 API Key 一律替换为 [REDACTED]。
 * 空白串跳过；返回新字符串，不改原文。
 */
internal fun redactSecrets(text: String, vararg secrets: String): String {
    var out = text
    for (secret in secrets) {
        if (secret.isNotBlank()) out = out.replace(secret, "[REDACTED]")
    }
    return out
}

/**
 * 把 HTTP 错误响应整理成一句人话：优先取 JSON 里的 message 字段（OpenAI/Anthropic 都是
 * error.message 形状），HTML 错误页给明确提示，避免满屏报文刷屏。
 */
fun formatApiError(httpCode: Int, body: String): String {
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
        try {
            val o = JSONObject(trimmed)
            val errObj = o.optJSONObject("error")
            val detail = errObj?.optString("message", "")?.takeIf { it.isNotBlank() }
                ?: o.optString("message", "").takeIf { it.isNotBlank() }
                ?: o.optString("detail", "").takeIf { it.isNotBlank() }
            if (detail != null) {
                // 保留错误码/类型：上层降级重试逻辑依赖其中的关键词（unsupported_parameter 等）
                val code = listOf(
                    errObj?.optString("code", ""),
                    errObj?.optString("type", ""),
                    o.optString("code", "")
                ).firstOrNull { !it.isNullOrBlank() && it != "null" }
                return "HTTP $httpCode：" + (if (code != null) "[$code] " else "") + detail.take(200)
            }
        } catch (_: Exception) {
        }
    }
    if (trimmed.startsWith("<")) {
        return "HTTP $httpCode：服务返回了网页而不是接口数据（Base URL 可能填错了）"
    }
    return if (trimmed.isBlank()) "HTTP $httpCode" else "HTTP $httpCode：${capRawBlob(trimmed)}"
}

/**
 * 错误体捕获窗口：JSON 报错的 error.message 常落在 300 字符之外（计费/任务型网关的
 * 长报文，error 对象排在 client_job_id/created_at 等元数据之后），窗口太小会把 JSON
 * 截断到解析失败，退化成裸报文丢掉真实原因。只影响保留的字符串，网络读不受影响。
 */
internal const val ERROR_BODY_CAPTURE = 1200

/**
 * 裸报文兜底的展示上限：太长时保留头尾（尾部常有 unsupported_parameter 等
 * 降级链关键词），中间省略；可解析的 JSON 在上面分支已被提取成人话，到不了这里。
 */
private fun capRawBlob(text: String): String =
    if (text.length <= 600) text
    else text.take(440) + "\n…[报文过长，中间略]…\n" + text.takeLast(160)

/** 错误体提及 stream_options → 视为上游不认该参数，摘除后重试 */
internal fun shouldDropStreamOptions(errorBody: String): Boolean =
    errorBody.contains("stream_options")

/** 流式请求重试的内部信号：带状态码跳出本轮，外层退避后重发 */
internal class RetrySignal(val code: Int) : Exception()

/** 鉴权被拒的内部信号：跳出本轮，换另一种鉴权方言立即重发（不占用瞬时重试额度） */
internal class AuthFallbackSignal(val code: Int) : Exception()

/** 可重试的瞬时状态码（顶层常量，避免每次请求重新分配） */
internal val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)

class ApiException(message: String) : Exception(message)
