package com.wavex.agent.core.domain.chat

import com.wavex.agent.core.domain.model.ApiProtocol

/**
 * 模型对「思考等级」的真实支持形态。
 *
 * 设置页只有一张统一的档位表（[ReasoningLevels]），但同一档位在不同模型上落到请求体的
 * 东西完全不一样：gpt-5 系收 `reasoning_effort`，GLM/Qwen/Kimi 只认「开/关」，DeepSeek
 * 什么都不认（思考由模型名决定），Anthropic 收的是 `thinking.budget_tokens`。
 * 用户需要看到的是「这个档位对这个模型到底意味着什么」，而不是自己在设置页点的那一格。
 *
 * 判定必须是纯函数且与 Provider 无关：payload 层按同一套规则决定发什么，UI 层按同一套
 * 规则解释「已生效的是什么」，两边共用这一个来源，杜绝「界面说高档、请求发的是
 * enable_thinking:true」的漂移。
 */
enum class ReasoningSupport {
    /** 档位按值原样透传（`reasoning_effort: low/medium/high/xhigh`） */
    TIERED,

    /** 只有开/关：选任何档位都等于「开启思考」，具体深度由模型自己决定 */
    TOGGLE,

    /** 思考由模型名/服务端决定，客户端发任何参数都会被拒或被静默忽略 */
    MODEL_DECIDED,
}

/** 模型名前缀 → 支持形态。与 payload 层的分支顺序一致（先判 deepseek 再判 glm…） */
fun reasoningSupportOf(model: String, protocol: ApiProtocol): ReasoningSupport {
    // Anthropic 协议是「预算」制：low/medium/high/xhigh 各对应一档 budget_tokens，
    // 不存在开/关族那套退化，也不存在「发什么都不认」的模型
    if (protocol == ApiProtocol.ANTHROPIC) return ReasoningSupport.TIERED
    val m = model.trim()
    return when {
        // DeepSeek 官方 API 没有思考开关：思考由模型名决定（deepseek-reasoner 之类）
        m.startsWith("deepseek", ignoreCase = true) -> ReasoningSupport.MODEL_DECIDED
        m.startsWith("glm", ignoreCase = true) -> ReasoningSupport.TOGGLE
        m.startsWith("qwen", ignoreCase = true) -> ReasoningSupport.TOGGLE
        m.startsWith("kimi", ignoreCase = true) -> ReasoningSupport.TOGGLE
        else -> ReasoningSupport.TIERED
    }
}

/**
 * 一轮请求里思考参数**实际**是什么。UI 直接渲染这个，而不是渲染用户选的档位。
 *
 * - [requested] 是用户在设置页选的档位（`""` = 默认）
 * - [level] 是真正生效的档位：开/关族里选任何非默认档位都记为 `"on"`（模型自己定深度），
 *   默认档位为 null（不发参数）
 * - [dropped] 为 true 表示本轮曾被上游拒绝后降级掉（不再发思考参数）
 */
data class EffectiveReasoning(
    val requested: String,
    val support: ReasoningSupport,
    val level: String?,
    val dropped: Boolean = false,
) {
    /** 是否真的往请求里塞了思考参数 */
    val applied: Boolean get() = !dropped && level != null

    /**
     * 一行可读文案，对话页状态条与设置页说明共用：
     * - `高`：档位式模型真的收到了 high
     * - `开`：开/关族，选任何档位都只等于打开
     * - `模型决定`：客户端发不了参数
     * - `关闭`：默认档位（跟随模型）
     * - `已降级`：本轮被拒，参数已摘掉
     */
    val label: String
        get() = when {
            dropped -> "已降级"
            // 支持形态先于 level 判：模型决定型的 level 永远是 null，
            // 若先判 level 会把它显示成「关闭」——而它其实开着，只是客户端控制不了
            support == ReasoningSupport.MODEL_DECIDED -> "模型决定"
            level == null -> "关闭"
            support == ReasoningSupport.TOGGLE -> "开"
            else -> ReasoningLevels.labelOf(level)
        }

    /** 长按/展开时的解释：为什么显示成这个值 */
    val detail: String
        get() = when {
            dropped -> "当前模型拒绝了思考参数，本轮已摘掉后重发"
            support == ReasoningSupport.MODEL_DECIDED ->
                "该模型的思考由模型名决定，客户端发参数会被拒或被静默忽略"
            support == ReasoningSupport.TOGGLE ->
                "该模型只支持开/关思考，不区分档位；选任何档位都等于开启"
            level == null -> "未发送思考参数，由模型使用默认行为"
            else -> "请求体里按所选档位原样下发（${ReasoningLevels.labelOf(level)}）"
        }

    companion object {
        /** 未发起生成时的预览：按当前设置与当前模型算出「下一次会发什么」 */
        fun preview(requested: String, model: String, protocol: ApiProtocol): EffectiveReasoning {
            val support = reasoningSupportOf(model, protocol)
            val level = when {
                requested.isBlank() -> null
                support == ReasoningSupport.MODEL_DECIDED -> null
                support == ReasoningSupport.TOGGLE -> "on"
                else -> requested
            }
            return EffectiveReasoning(requested = requested, support = support, level = level)
        }
    }
}
