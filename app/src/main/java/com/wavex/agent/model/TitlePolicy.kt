package com.wavex.agent.model

/**
 * 标题演化策略（cc-switch 调研后的方案乙：首题 + 里程碑演化）：
 * - 首轮完成后生成首题（标题还是「新对话」占位时）；
 * - 此后每新增 EVOLVE_EVERY 条用户消息，用最近 RECENT_TURNS 轮重新生成一次
 *   （滑动窗口输入同 Open WebUI {{MESSAGES:END:2}} / CherryStudio 最近 5 条的做法）；
 * - 手动改名（titleIsUserDefined）是硬闸门：自动标题（首题与演化）永不再覆盖；
 * - 生成失败时调用方保留旧标题、基线不推进，下个里程碑自然重试。
 */
internal object TitlePolicy {
    /** 每新增多少条用户消息演化一次标题 */
    const val EVOLVE_EVERY = 6

    /** 演化时转录最近几轮（1 轮 = 用户+助手两条） */
    const val RECENT_TURNS = 3

    private const val PER_MESSAGE_MAX = 300

    /**
     * 返回交给标题模型的转录文本；不需要（重）生成时返回 null。
     * lastTitleUserCount = 上次标题生成时的用户消息数（演化基线），随会话持久化。
     */
    fun transcript(
        titleIsUserDefined: Boolean,
        title: String,
        messages: List<ChatMessage>,
        lastTitleUserCount: Int
    ): String? {
        if (titleIsUserDefined) return null
        val real = messages.filterNot { it.isError }
        if (real.isEmpty()) return null
        val userCount = real.count { it.fromUser }
        val recent = when {
            title == "新对话" -> real.takeLast(2)                      // 首题：首轮问答
            userCount - lastTitleUserCount >= EVOLVE_EVERY -> real.takeLast(RECENT_TURNS * 2)
            else -> return null
        }
        return recent.joinToString("\n") { m ->
            (if (m.fromUser) "用户：" else "助手：") + m.text.take(PER_MESSAGE_MAX).ifBlank { "（图片/附件）" }
        }
    }
}
