package com.wavex.agent

import android.content.Intent
import androidx.activity.viewModels
import com.wavex.agent.feature.conversation.viewmodel.ConversationViewModel
import com.wavex.agent.feature.catalog.CatalogViewModel
import com.wavex.agent.feature.usage.UsageViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wavex.agent.feature.conversation.ui.sharedAttachmentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue

import com.wavex.agent.core.domain.model.ChatAttachment
import com.wavex.agent.ui.AgentApp
import com.wavex.agent.core.data.attachment.AttachmentLoader

class MainActivity : ComponentActivity() {
    private val conversationState: ConversationViewModel by viewModels {
        val c = (application as WavexApplication).container
        viewModelFactory {
            initializer {
                ConversationViewModel(
                    c.conversationRepository,
                    c.providerStore,
                    c.modelService,
                    c.contentLoader,
                    c.generatedFileWriter
                )
            }
        }
    }
    private val usageState: UsageViewModel by viewModels {
        viewModelFactory {
            initializer { UsageViewModel((application as WavexApplication).container.usageRepository) }
        }
    }
    private val catalogState: CatalogViewModel by viewModels {
        val c = (application as WavexApplication).container
        viewModelFactory {
            initializer {
                CatalogViewModel(
                    c.providerStore,
                    c.conversationRepository,
                    c.modelService
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 长按选词结束后 foundation 会异步启动 TextClassifier（"智能选词"）回写选区，
        // 若用户此刻已清除选择，回写会复活已清空的选区并使浮动工具条闪现（只有"全选"）。
        // 关闭该功能以根除此问题；代价是长按选中的是单词本身而非系统智能扩展的词组。
        @OptIn(ExperimentalFoundationApi::class)
        run {
            ComposeFoundationFlags.isSmartSelectionEnabled = false
            // 新上下文菜单管线中，浮动工具条由 SnapshotStateObserver 观察选区状态并在变化时
            // actionMode.invalidate()：清除选区（selection=null）会触发菜单重建——Copy 因禁用
            // 被移除、SelectAll 因 subselections 为空仍保留——与排队的 finish() 竞态，
            // 产生“只有全选”的浮条闪现。切回旧管线（showMenu/hide 直接同步控制，无 invalidate）根除。
            ComposeFoundationFlags.isNewContextMenuEnabled = false
        }
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgentApp(conversationState, catalogState, usageState, (application as WavexApplication).container) }
        handleSharedIntent(intent)
    }
    /** 授权页/权限弹窗返回（或应用切回前台）后重解析会话存储目录：必要时搬移会话文件，
     *  并刷新设置页「会话存储位置」的状态。首次启动也走这里（onResume 早于首帧组合）。 */
    override fun onResume() {
        super.onResume()
        catalogState.refreshSessionStorage()
    }

    /** 应用在后台时再收到分享（singleTask）：交给现有会话处理 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
                conversationState.consumeSharedIntent(this, intent)
        }
    }
}

/**
 * 接收系统分享：文本 → 填入当前会话输入框（可继续编辑）；
 * 文件/图片 → 加入当前会话附件列表。多份分享逐个追加。
 */
private fun ConversationViewModel.consumeSharedIntent(context: android.content.Context, intent: Intent) {
    val streamUris = extractSharedStreamUris(intent)
    when (intent.action) {
        Intent.ACTION_SEND -> when {
            streamUris.isNotEmpty() -> streamUris.forEach { addSharedAttachment(context, it) }
            // 纯文本分享：追加到输入框草稿（保留已输入内容）并通知输入框重读
            else -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
                val merged = (draftInputFor(currentConversationId) + "\n" + text).trim()
                setDraftInput(currentConversationId, merged)
                draftVersions[currentConversationId] = (draftVersions[currentConversationId] ?: 0) + 1
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> streamUris.forEach { addSharedAttachment(context, it) }
    }
}

/**
 * 从分享意图提取流 Uri，兼容三类真实载荷：
 * 1. EXTRA_STREAM = 单个 Uri（标准形态）；
 * 2. EXTRA_STREAM = ArrayList<Uri>（不少来源 App 单文件也走数组）：typed
 *    getParcelableExtra 遇类型不匹配只吞 ClassCastException 返回 null，
 *    旧实现因此把整个分享静默丢弃（无附件无提示，Bundle 日志可证）；
 *    API<33 的裸 getter 更会直接 CCE 崩溃；
 * 3. 流只挂在 ClipData（授权随 ClipData 走的分享来源）。
 * 以上都取不到时，SEND 且 MIME 非 text 的 content: data 兜底
 * （个别来源把流放在 data；text 类 MIME 排除，以免把网页链接误当附件）。
 */
internal fun extractSharedStreamUris(intent: Intent): List<android.net.Uri> {
    // extras?.get 返回未装箱的原始对象（Uri / ArrayList<Uri> / …），
    // 不走会因类型不匹配抛 CCE 的强转 getter，形态交给下方 is 分派判断
    val raw: Any? = try {
        intent.extras?.get(Intent.EXTRA_STREAM)
    } catch (_: Exception) {
        null
    }
    val uris = mutableListOf<android.net.Uri>()
    when (raw) {
        is android.net.Uri -> uris.add(raw)
        is List<*> -> uris.addAll(raw.filterIsInstance<android.net.Uri>())
    }
    if (uris.isEmpty()) {
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
    }
    if (uris.isEmpty() && intent.action == Intent.ACTION_SEND) {
        val data = intent.data
        val mime = intent.type ?: ""
        if (data != null && "content" == data.scheme && !mime.startsWith("text/")) {
            uris.add(data)
        }
    }
    return uris
}

private fun ConversationViewModel.addSharedAttachment(context: android.content.Context, uri: android.net.Uri) {
    // 第一层筛选（软件级）：分享来的软件不支持的类型直接报错丢弃，不上传浪费时间
    val name = sharedAttachmentName(context, uri)
    val reason = AttachmentLoader.unsupportedReason(context, uri, name)
    if (reason != null) {
        Toast.makeText(context, "「$name」$reason", Toast.LENGTH_LONG).show()
        return
    }
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // 分享来的临时授权不支持持久化，本次会话内仍可读取
    }
    attachmentsFor(currentConversationId).add(ChatAttachment(uri.toString(), name))
}
