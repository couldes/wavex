package com.wavex.agent.engine

/**
 * 思考档位的单一事实来源：设置页分段控件、payload 映射、旧值归一化共用一份，避免两处漂移。
 * "" = 不传参数（跟随模型默认）；低/中/高/极致一一对应 low/medium/high/xhigh，均匀递进。
 * 不设「极低」：minimal 仅初代 gpt-5 支持，gpt-5.1 起全部移除（cc-switch 映射表同款结论）。
 */
internal object ReasoningLevels {

    /** UI 分段控件用的「值 to 中文短标签」列表。 */
    val displayLevels: List<Pair<String, String>> = listOf(
        "" to "默认",
        "low" to "低",
        "medium" to "中",
        "high" to "高",
        "xhigh" to "极高"
    )

    // 合法档位从 displayLevels 派生（剔除「默认」的空串）：加档位只改上面一张表，归一化自动跟上
    private val known = displayLevels.map { it.first }.toSet() - ""

    /**
     * 归一化持久层读出的旧值：
     * - minimal（已移除的「极低」）归一化为 low（最接近的现存档位）；
     * - 未知值（上游以后又改档位名）回退「默认」，宁可不发参数也不发错的。
     */
    fun normalize(v: String): String = when (v) {
        in known -> v
        "minimal" -> "low"
        else -> ""
    }
}
