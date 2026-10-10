package com.wavex.agent.feature.conversation.ui

/**
 * 发送前是否已配置好服务商（与 ChatScreen.sendMessage 的判断保持一致）：
 * 没有 provider、或 Base URL / API Key 为空都视为未配置。
 */
internal fun isProviderConfigured(
    baseUrl: String?,
    apiKey: String?,
    hasProvider: Boolean
): Boolean = hasProvider && !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank()

/**
 * 未配置服务商时用户消息保留的文本：原文 trim，绝不丢弃。
 *
 * 「仅附件无文字」时返回 ""——但消息仍会带上附件（与已配置分支一致，附件本身
 * 即消息内容，渲染时不会出现空胶囊）；纯文字时保留原文，用户配置好服务商后
 * 可长按复制或就地编辑重发。
 */
internal fun unconfiguredUserMessageText(input: String): String = input.trim()
