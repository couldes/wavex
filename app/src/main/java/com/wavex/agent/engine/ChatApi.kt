package com.wavex.agent.engine

import com.wavex.agent.model.ChatAttachment

/**
 * 附件内容加载接口：历史构建只依赖此抽象，测试用假实现驱动，
 * 生产环境由 data.AttachmentLoader 提供（见 HistoryBuilder.systemLoader）。
 *
 * （原同文件的 engine.ChatApi 流式接口已移除：ApiClient 为让 streamChat 携带
 * 带默认值的 onImage 参数改为独立 object 后，该接口全仓库无实现与引用；
 * 若将来重新启用接口抽象，从 git 历史找回并同步补 onImage 形参。）
 */
internal fun interface ContentLoader {
    suspend fun load(attachment: ChatAttachment): Pair<String, String>?
}
