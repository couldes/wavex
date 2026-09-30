package com.wavex.agent

import android.app.Application
import android.content.Context
import com.wavex.agent.data.ConversationStore
import com.wavex.agent.data.ProviderStore

/**
 * 手动构造注入容器：两个持久化 Store 在进程内唯一。
 * 不引 DI 框架，WavexApplication 持有、ViewModel 工厂取用。
 */
internal class AppContainer(appContext: Context) {
    val providerStore by lazy { ProviderStore(appContext) }
    val conversationStore by lazy { ConversationStore(appContext) }

    /**
     * 自动外部备份落点：应用专属外部目录（Android/data/…/files/backup/），
     * 读写无需权限，卸载后多数机型保留 —— 重装时从中自动找回对话。
     * 外部存储不可用时为 null（自动备份静默跳过）。
     */
    val conversationBackupFile: java.io.File? by lazy {
        appContext.getExternalFilesDir(null)?.let { java.io.File(it, "backup/conversations-auto.json") }
    }
}

internal class WavexApplication : Application() {
    val container = AppContainer(this)
}
