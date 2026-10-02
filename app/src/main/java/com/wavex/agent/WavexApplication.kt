package com.wavex.agent

import android.app.Application
import android.content.Context
import com.wavex.agent.data.ConversationStore
import com.wavex.agent.data.ProviderStore
import com.wavex.agent.data.SafBackupStore
import com.wavex.agent.data.UsageStore
import com.wavex.agent.network.UsageSink
import com.wavex.agent.network.UsageTracker

/**
 * 手动构造注入容器：持久化 Store 在进程内唯一。
 * 不引 DI 框架，WavexApplication 持有、ViewModel 工厂取用。
 */
internal class AppContainer(appContext: Context) {
    val providerStore by lazy { ProviderStore(appContext) }
    val conversationStore by lazy { ConversationStore(appContext) }

    /**
     * SAF 备份文件夹：卸载重装后仍存在、可恢复的可靠落点。
     * 用户在设置页选定文件夹后自动备份；授权不跨卸载保留，重装后需重新授权一次。
     * 不再用应用专属外部目录（Android/data/…）做备份：那个目录卸载时被系统
     * 整体删除，防不了卸载，只是多一层假象（历史版本曾如此，已移除）。
     */
    val safBackupStore by lazy { SafBackupStore(appContext) }

    /** 用量统计存储：埋点写入与统计页查询共用同一实例 */
    val usageStore by lazy { UsageStore(appContext) }

    init {
        // 埋点接线：ApiClient 请求终态 → UsageSink → UsageStore。
        // UsageTracker.record 自身 runCatching，这里不再包一层。
        UsageTracker.sink = UsageSink { usageStore.record(it) }
    }
}

internal class WavexApplication : Application(), coil.ImageLoaderFactory {
    val container = AppContainer(this)

    /** coil-svg 全局注册：模型返回的内联 SVG 以 .svg 附件落盘后，缩略图/查看器才能真正渲染 */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .components { add(coil.decode.SvgDecoder.Factory()) }
            .build()
}
