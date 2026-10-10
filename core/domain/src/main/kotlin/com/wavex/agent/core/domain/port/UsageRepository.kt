package com.wavex.agent.core.domain.port

import com.wavex.agent.core.domain.model.UsagePageSnapshot

/**
 * 用量统计查询端口：统计页一屏组合查询（单次读盘解析产出全部五项）。
 * 实现由 data 层提供（UsageStore）。setOnRecorded：有新记录落账后的回调
 * （UI 据此刷新统计页）；传 null 撤销。
 */
interface UsageRepository {
    fun pageData(startMs: Long, endMs: Long, providerId: String?, limit: Int = 50): UsagePageSnapshot
    fun setOnRecorded(listener: (() -> Unit)?)
}
