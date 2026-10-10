package com.wavex.agent.core.network

import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.core.domain.port.UsageSink
import okhttp3.OkHttpClient

/** 保持生产默认读超时；独立 HTTP 会话可以使用有界的测试配置。 */
internal data class ApiReadTimeouts(
    val firstDataMs: Long = 5_000L,
    val streamMs: Long = 120_000L
)

/**
 * 网络实现装配的唯一入口：App 以 OkHttp 客户端 + 用量 sink 换取完整 ModelService。
 * 请求执行、鉴权方言缓存等会话状态由 HttpApiSession 按实例持有（对模块外不可见）；
 * 请求终态经 recordUsage 回调交给 UsageTracker.recordWithSink 显式落账。
 */
fun createModelService(client: OkHttpClient, usageSink: UsageSink): ModelService =
    HttpApiSession(
        client,
        ApiReadTimeouts(),
        recordUsage = { kind, provider, usage, code, latency, error ->
            UsageTracker.recordWithSink(usageSink, kind, provider, usage, code, latency, error)
        }
    )
