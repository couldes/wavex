package com.wavex.agent

import android.content.Intent
import androidx.activity.viewModels
import com.wavex.agent.state.WavexViewModel
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue

import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.ui.AgentApp
import com.wavex.agent.data.AttachmentLoader

class MainActivity : ComponentActivity() {
    private val agentState: WavexViewModel by viewModels { WavexViewModel.factory((application as WavexApplication).container) }

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
        setContent { AgentApp(agentState) }
        handleSharedIntent(intent)
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
                agentState.consumeSharedIntent(this, intent)
        }
    }
}

/** 共享附件的显示名：优先查询 DISPLAY_NAME（分享 URI 的 lastPathSegment 往往是数字 ID） */
internal fun sharedAttachmentName(context: android.content.Context, uri: android.net.Uri): String {
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
    }
    // 兑底：uri 路径形如 primary:Download/xxx.pdf 或 content/…/1，
    // 取最后一段真实文件名，避免「model 看到的还是 uri 路径」
    val fallback = uri.lastPathSegment ?: return "附件"
    return fallback.substringAfterLast('/').substringAfterLast(':').ifBlank { "附件" }
}
/**
 * 接收系统分享：文本 → 填入当前会话输入框（可继续编辑）；
 * 文件/图片 → 加入当前会话附件列表。多份分享逐个追加。
 */
private fun WavexViewModel.consumeSharedIntent(context: android.content.Context, intent: Intent) {
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

private fun WavexViewModel.addSharedAttachment(context: android.content.Context, uri: android.net.Uri) {
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
