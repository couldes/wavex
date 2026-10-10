package com.wavex.agent.core.domain.port

import com.wavex.agent.core.domain.model.UsageLogEntry

/**
 * 用量埋点出口：网络实现在请求终态调用，App 侧实现落到 UsageStore。
 * 接口化解耦：网络模块不依赖持久化，测试注入 fake 收集器即可断言。
 */
fun interface UsageSink {
    fun record(entry: UsageLogEntry)
}
