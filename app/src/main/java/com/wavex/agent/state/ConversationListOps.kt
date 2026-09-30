package com.wavex.agent.state

import com.wavex.agent.model.AgentConversation

/**
 * 空对话的复用与清理规则（纯函数，可单测；见 ConversationListOpsTest）。
 * 「空」= 未发消息且未改名 —— 改过名的空对话视为用户有意保留，既不复用也不清理。
 */

/** 未发消息的空草稿对话（标题仍是「新对话」） */
internal fun isEmptyScratch(conversation: AgentConversation): Boolean =
    conversation.messages.isEmpty() && conversation.title == "新对话"

/**
 * 「新建对话」的复用：已存在空草稿对话时直接切过去（挪到列表顶部由调用方做），
 * 不再每次都新建一个、堆出一排空「新对话」。
 */
internal fun findReusableEmptyConversation(conversations: List<AgentConversation>): AgentConversation? =
    conversations.firstOrNull { isEmptyScratch(it) }

/**
 * 启动加载 / 导入后的清理：丢弃历史积累的空草稿对话；
 * 若全部都是空草稿，保留第一个作为可用的当前会话（应用至少要有一个对话）。
 */
internal fun dropExtraEmptyConversations(conversations: List<AgentConversation>): List<AgentConversation> {
    val nonEmpty = conversations.filterNot { isEmptyScratch(it) }
    if (nonEmpty.isNotEmpty()) return nonEmpty
    return conversations.take(1)
}
