package com.wavex.agent.core.domain.model

/**
 * 一轮对话里「联网」的真实状态。
 *
 * 输入栏的联网胶囊只表达**意图**（用户点亮的开关）。用户真正想知道的是：这一轮到底联没联网、
 * 搜了几次、有没有在发出请求前就被上游拒掉。三者都可能与开关不一致：
 * - 搜索源未配置 → 开关亮着也发不出工具声明（[toolDeclared] 为 false）；
 * - 上游拒绝 tools 声明 → 降级链摘掉联网重发（[dropped] 为 true）；
 * - 模型自己决定不搜 → 声明发出去了、但一次都没调用（[searches] 为 0）。
 *
 * 与 [com.wavex.agent.core.domain.chat.EffectiveReasoning] 同构：UI 渲染这个对象，
 * 而不是渲染开关本身。
 */
data class EffectiveWebSearch(
    /** 用户在输入栏点的开关 */
    val enabled: Boolean,
    /** 本轮请求体里是否真的带了 tools 声明 */
    val toolDeclared: Boolean,
    /** 搜索源是否可用（未配置时即便声明也执行不了） */
    val sourceReady: Boolean,
    /** 本轮是否因上游拒绝而降级掉联网 */
    val dropped: Boolean = false,
    /** 本轮实际执行的搜索次数（模型主动调用 web_search 的次数） */
    val searches: Int = 0,
    /** 本轮搜索是否全部成功（有失败时状态条给出失败提示，详情在工具卡片里） */
    val allSucceeded: Boolean = true,
) {
    /** 联网是否真的处于生效状态 */
    val active: Boolean get() = !dropped && toolDeclared && sourceReady

    /**
     * 一行可读文案，对话页状态条用：
     * - `已搜 2 次`：真的搜了
     * - `待命`：声明已发，模型这轮没用
     * - `未配置`：搜索源缺失
     * - `已降级`：上游拒了 tools
     * - `关闭`：用户没开
     */
    val label: String
        get() = when {
            !enabled -> "关闭"
            dropped -> "已降级"
            !sourceReady -> "未配置"
            searches > 0 -> if (allSucceeded) "已搜 $searches 次" else "搜索失败"
            else -> "待命"
        }

    /** 长按/展开时的解释 */
    val detail: String
        get() = when {
            !enabled -> "未在请求中声明搜索工具"
            dropped -> "当前模型拒绝了工具声明，本轮已摘掉联网后重发"
            !sourceReady -> "未配置搜索源，无法执行本机搜索（设置 → 联网搜索）"
            searches > 0 && allSucceeded -> "模型本轮主动发起了 $searches 次搜索，结果已回填"
            searches > 0 -> "模型本轮发起了 $searches 次搜索，其中有失败项，详见气泡里的工具卡片"
            else -> "搜索工具已声明，但模型本轮判断不需要联网"
        }

    companion object {
        /** 未发起生成时的预览：只反映「开关 + 搜索源」两个已知条件 */
        fun preview(enabled: Boolean, sourceReady: Boolean): EffectiveWebSearch =
            EffectiveWebSearch(
                enabled = enabled,
                toolDeclared = enabled && sourceReady,
                sourceReady = sourceReady,
            )
    }
}
