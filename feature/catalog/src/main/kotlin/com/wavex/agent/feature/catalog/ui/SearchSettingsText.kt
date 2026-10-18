package com.wavex.agent.feature.catalog.ui

/**
 * 设置页「联网搜索」分区的文案（spec §7.3，v1 只读）。
 *
 * 抽成常量而不是内联在 Composable 里：文案本身就是这一版的功能内容
 * （告诉用户搜索源是什么、流量走哪里），用 JVM 单测钉住比 UI 测试便宜得多。
 */
internal object SearchSettingsText {
    const val SECTION_TITLE = "联网搜索"
    const val SOURCE_LINE = "搜索源：Bing（内置，无需配置）"
    const val LOCAL_NOTE = "搜索请求由本机发起，不经过中转站"
}
