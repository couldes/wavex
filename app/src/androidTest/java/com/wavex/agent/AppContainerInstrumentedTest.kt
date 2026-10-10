package com.wavex.agent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组合根装配契约钉子（Task 10 Step 1）：AppContainer 必须以 domain 端口
 * 暴露全部仓库/服务；不触网、不改用户数据（所有依赖惰性单例，只取引用）。
 */
class AppContainerInstrumentedTest {

    private fun container(): AppContainer {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as WavexApplication
        return app.container
    }

    @Test
    fun containerExposesConversationRepository() {
        val c = container()
        val repo: com.wavex.agent.core.domain.port.ConversationRepository = c.conversationRepository
        assertNotNull(repo)
    }

    @Test
    fun containerExposesProviderSettingsRepository() {
        val c = container()
        val settings: com.wavex.agent.core.domain.port.ProviderSettingsRepository = c.providerStore
        assertNotNull(settings)
    }

    @Test
    fun containerExposesUsageRepository() {
        val c = container()
        val usage: com.wavex.agent.core.domain.port.UsageRepository = c.usageRepository
        assertNotNull(usage)
    }

    @Test
    fun containerExposesModelService() {
        val c = container()
        val service: com.wavex.agent.core.domain.port.ModelService = c.modelService
        assertNotNull(service)
    }

    @Test
    fun containerExposesAttachmentPorts() {
        val c = container()
        assertNotNull(c.contentLoader)
        assertNotNull(c.generatedFileWriter)
        assertNotNull(c.attachmentSaver)
        assertNotNull(c.attachmentSelectionPolicy)
    }

    @Test
    fun repositoriesAreProcessWideSingletons() {
        val c = container()
        // 同一容器两次取用：惰性单例同一实例（进程内唯一持久化归属）
        assertTrue(c.conversationRepository === c.conversationRepository)
        assertTrue(c.modelService === c.modelService)
    }
}
