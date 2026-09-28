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
}

internal class WavexApplication : Application() {
    val container = AppContainer(this)
}
