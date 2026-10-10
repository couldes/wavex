package com.wavex.agent.core.domain.model

/**
 * 会话落盘目录状态（设置页「数据」分区展示，Task 8 消费）：
 * usingPublic = 位于公共目录（卸载重装后保留）；path = 当前 sessions 目录绝对路径；
 * canRequest = 尚未用上公共目录且设备档位可引导授权升档（API 26 以下恒 false）。
 */
data class SessionStorageStatus(
    val usingPublic: Boolean,
    val path: String,
    val canRequest: Boolean
)
