package com.wavex.agent.core.domain.model

import java.util.UUID

/**
 * 服务商配置（参考 ccswitch 的设计：一个服务商 = 名称 + Base URL + API Key + 模型）。
 * 预设模板让用户尽量少填东西；中转站/自建服务直接选"自定义"。
 */
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String
)
