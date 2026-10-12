package com.wavex.agent.feature.catalog

/** 内置预设：点一下就填好名称与官方 Base URL，用户只需要粘贴 API Key；模型交给添加后的自动拉取。 */
data class ProviderPreset(
    val label: String,
    val name: String,
    val baseUrl: String
)

val PROVIDER_PRESETS = listOf(
    // OpenAI / Claude 放最前：两大协议的官方入口。Base URL 指向 anthropic.com 时自动切换
    // Anthropic 协议（/v1/messages + x-api-key），其余走 OpenAI 兼容（/chat/completions）。
    // 不内置默认/备选模型：模型名过期速度远快于域名（厂商改版即失效），统一靠添加后自动拉取。
    ProviderPreset("OpenAI", "OpenAI", "https://api.openai.com/v1"),
    ProviderPreset("Claude", "Claude", "https://api.anthropic.com"),
    ProviderPreset("DeepSeek", "DeepSeek", "https://api.deepseek.com/v1"),
    ProviderPreset("Kimi", "Kimi", "https://api.moonshot.cn/v1"),
    ProviderPreset("通义千问", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    ProviderPreset("智谱 GLM", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
    // 自定义：Base URL 完全留空（用户自己填），拉取不到模型列表时由用户在模型页选择
    ProviderPreset("自定义", "", "")
)
