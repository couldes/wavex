package com.wavex.agent

import android.content.Context
import com.wavex.agent.core.data.GeneratedFileStore
import com.wavex.agent.core.data.ProviderStore
import com.wavex.agent.core.data.UsageStore
import com.wavex.agent.core.data.attachment.DefaultAttachmentLoader
import com.wavex.agent.core.data.attachment.GeneratedAttachmentWriter
import com.wavex.agent.core.data.repository.DefaultConversationRepository
import com.wavex.agent.core.domain.chat.ContentLoader
import com.wavex.agent.core.domain.port.GeneratedFileWriter
import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.core.domain.port.SearchProvider
import com.wavex.agent.core.network.BingRssSearchProvider
import com.wavex.agent.core.domain.port.UsageSink
import com.wavex.agent.core.network.createModelService
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(private val appContext: Context) {
    val providerStore by lazy { ProviderStore(appContext) }

    /** 生成文件落盘（模型返回的 base64 附件 → filesDir/generated/）；孤儿清扫共用同一目录绑定 */
    val generatedFileStore by lazy { GeneratedFileStore(java.io.File(appContext.filesDir, "generated")) }

    /** 用户附件入口快照落盘（挑选拍下/分享 → filesDir/attachments/）；孤儿清扫与仓库共用同一目录绑定 */
    val intakeAttachmentStore by lazy {
        com.wavex.agent.core.data.attachment.IntakeAttachmentStore(java.io.File(appContext.filesDir, "attachments"))
    }

    /**
     * 会话仓库：逐会话 JSONL 持久化的唯一入口（迁移/加载/同步/导入/目录升档都封在 data 层）。
     * 文件壳类型（会话存储/编解码/diff）均为 core.data 内部类型，app 不再直接持有。
     */
    val conversationRepository by lazy {
        DefaultConversationRepository(appContext, generatedFileStore, intakeAttachmentStore)
    }

    /** 用量统计存储（data 层实现）：埋点写入（UsageSink）与统计页查询（UsageRepository）共用同一实例 */
    val usageStore: com.wavex.agent.core.data.UsageStore by lazy { UsageStore(appContext) }

    /** 端口视图：feature 模块只依赖 domain 端口 */
    val usageRepository: com.wavex.agent.core.domain.port.UsageRepository get() = usageStore

    /** 附件内容加载（图片→视觉消息、音频→input_audio、文本→内联）：构造于 composition root */
    val contentLoader: ContentLoader by lazy { DefaultAttachmentLoader(appContext) }

    /** 生成文件落盘端口（模型返回的 base64 附件 → generatedFileStore） */
    val generatedFileWriter: GeneratedFileWriter by lazy { GeneratedAttachmentWriter(generatedFileStore) }

    /** 用户附件入口快照端口（挑选拍下/分享时字节复制进 intakeAttachmentStore） */
    val attachmentSnapshotter: com.wavex.agent.core.domain.port.AttachmentSnapshotter by lazy {
        com.wavex.agent.core.data.attachment.IntakeAttachmentSnapshotter(appContext, intakeAttachmentStore)
    }

    /** 附件保存端口（图片→相册、其余→下载目录；MediaStore 实现留在 data 层） */
    val attachmentSaver: com.wavex.agent.core.domain.port.AttachmentSaver by lazy {
        com.wavex.agent.core.data.attachment.MediaAttachmentSaver(appContext)
    }

    /** 附件可选性策略端口（转不成 API 载荷的格式在选择期拦截） */
    val attachmentSelectionPolicy: com.wavex.agent.core.domain.port.AttachmentSelectionPolicy by lazy {
        com.wavex.agent.core.data.attachment.DefaultAttachmentSelectionPolicy(appContext)
    }

    /**
     * 网络服务：网络模块工厂装配（HTTP 执行已迁往 core:network）。
     * OkHttp 客户端超时与旧 ApiClient 全局客户端一致：connect 15s / read 120s；
     * 请求终态经 UsageSink 直接落入 usageStore（不再有全局 UsageTracker.sink）。
     */
    val modelService: ModelService by lazy {
        createModelService(httpClient, UsageSink { usageStore.record(it) })
    }

    /** 与模型请求共用一个 OkHttp 实例（连接池/线程池复用）；搜索是短请求，超时另设 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 联网搜索执行器：v1 内置 Bing RSS（零配置、无新依赖）。
     * 搜索由本机发起、不经过中转站——2026-10-17 实测服务端 web_search 在多数中转站上失效。
     * 后续加 SearXNG/Tavily 只需换实现，`SearchProvider` 端口不变。
     */
    val searchProvider: SearchProvider by lazy {
        BingRssSearchProvider(
            httpClient.newBuilder()
                // 搜索超时短得多：卡住的搜索会拖住整轮对话，宁可失败让模型自己决定是否重搜
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        )
    }
}
