package com.wavex.agent.feature.catalog.ui

import com.wavex.agent.core.domain.chat.ReasoningSupport
import com.wavex.agent.core.domain.chat.reasoningSupportOf
import com.wavex.agent.core.domain.model.ApiProtocols
import com.wavex.agent.core.domain.model.Provider

/**
 * 设置页「思考等级」的说明文案，按当前模型的真实支持形态生成。
 *
 * 抽成纯函数而不是内联在 Composable 里：这是用户唯一能读到「我的选择到底会不会生效」
 * 的地方，用 JVM 单测钉住比 UI 测试便宜得多（`SearchSettingsText` 同款思路）。
 *
 * 三种形态对应三句话，刻意不共用「不支持的模型会自动忽略」这种含糊说法——
 * 那句话对开/关族是错的（不是忽略，是退化成一个开关），对 DeepSeek 更是错的
 * （静默无效，用户以为在控制实际没控制）。
 */
internal fun reasoningHintOf(requested: String, provider: Provider?): String {
    if (provider == null || provider.model.isBlank()) {
        return "还没有配置服务商，选择会在配置后生效。"
    }
    return when (reasoningSupportOf(provider.model, ApiProtocols.detect(provider.baseUrl))) {
        ReasoningSupport.TIERED ->
            if (requested.isBlank()) "「默认」不发送任何参数，由模型自行决定思考深度。"
            else "该模型按档位生效：所选档位会作为参数原样下发。"
        ReasoningSupport.TOGGLE ->
            if (requested.isBlank()) "该模型只支持「开/关」思考：选「默认」等于关闭思考。"
            else "该模型只支持「开/关」思考：选任何档位都等于开启，深度由模型自己决定。"
        ReasoningSupport.MODEL_DECIDED ->
            "该模型的思考由模型名决定，本页的档位对它不起作用（发参数会被拒或被静默忽略）。"
    }
}
