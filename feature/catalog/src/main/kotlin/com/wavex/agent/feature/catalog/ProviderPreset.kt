package com.wavex.agent.feature.catalog

/** 内置预设：点一下就填好 Base URL 与默认模型，用户只需要粘贴 API Key。 */
data class ProviderPreset(
    val label: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val fallbackModels: List<String>
)

val PROVIDER_PRESETS = listOf(
    // OpenAI / Claude 放最前：国际主流服务商优先展示，国内服务商与自定义依次往后
    ProviderPreset("OpenAI", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
        listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")),
    // Claude：Base URL 指向 anthropic.com 时自动切换 Anthropic 协议（/v1/messages + x-api-key）
    ProviderPreset("Claude", "Claude", "https://api.anthropic.com", "claude-sonnet-4-5",
        listOf("claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-1")),
    // DeepSeek 现行模型为 deepseek-flash / deepseek-v4-pro（2026-02 官方定价页，
    // 旧名 deepseek-chat/deepseek-reasoner 已下线），anthropic 网关同理（core.network 的 ApiClient.anthropicFallbackModels）
    ProviderPreset("DeepSeek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash",
        listOf("deepseek-flash", "deepseek-v4-pro")),
    ProviderPreset("Kimi", "Kimi", "https://api.moonshot.cn/v1", "kimi-k2-0711-preview",
        listOf("kimi-k2-0711-preview", "moonshot-v1-8k", "moonshot-v1-32k")),
    ProviderPreset("通义千问", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
        listOf("qwen-plus", "qwen-turbo", "qwen-max")),
    ProviderPreset("智谱 GLM", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air",
        listOf("glm-4.5-air", "glm-4-plus", "glm-4-flash")),
    // 自定义：Base URL 完全留空（用户自己填），不预设默认模型——拉到列表后自动选第一个
    ProviderPreset("自定义", "", "", "",
        listOf())
)

