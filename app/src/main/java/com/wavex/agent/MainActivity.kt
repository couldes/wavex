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
    when (intent.action) {
        Intent.ACTION_SEND -> {
            val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            when {
                uri != null -> addSharedAttachment(context, uri)
                // 纯文本分享：追加到输入框草稿（保留已输入内容）并通知输入框重读
                else -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
                    val merged = (draftInputFor(currentConversationId) + "\n" + text).trim()
                    setDraftInput(currentConversationId, merged)
                    draftVersions[currentConversationId] = (draftVersions[currentConversationId] ?: 0) + 1
                }
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val uris: ArrayList<android.net.Uri>? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            uris?.forEach { addSharedAttachment(context, it) }
        }
    }
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
