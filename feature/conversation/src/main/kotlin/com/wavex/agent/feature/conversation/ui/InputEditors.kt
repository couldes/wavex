package com.wavex.agent.feature.conversation.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.wavex.agent.core.domain.model.ChatAttachment
import java.io.File

/**
 * 输入/编辑相关的独立单元：超长输入的放大编辑器、生成文件的系统打开入口。
 *
 * 从 ChatScreen.kt 拆出（Task 7 Step 3：attachment/save 与 input/editor 单元分离）。
 * 两者都不持有会话状态：编辑器只做文本双向同步，openGeneratedFile 只做 FileProvider
 * + ACTION_VIEW。
 */

/**
 * 正文超过这个字数后，输入框右上角浮出「放大编辑」入口。
 * 输入框约 7 行（160dp）封顶，120 字已接近/超过封顶，框内滚动编辑体验变差，
 * 提前一点给出全屏编辑入口。
 */
internal const val EXPAND_EDIT_THRESHOLD = 120

/**
 * 放大编辑：全屏编辑超长输入。顶部「取消 / 字数 / 发送」（左取消右发送，
 * 与就地编辑器同款布局），中间占满全屏的多行编辑区。
 * 不自动聚焦弹键盘：打开时可能只是想先通读检查，点编辑区再唤起键盘。
 * 文本实时同步回输入框（单一数据源）：取消＝收起不丢字；发送＝直接发出，
 * 发送条件与主输入栏一致（有文字或附件、非生成中）。
 */
@Composable
internal fun ExpandedInputEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    canSend: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        // 独立窗口默认 fits system windows，不会为 IME 重排 Compose 内容；
        // 关掉后由下方 imePadding() 消费 IME inset，键盘弹出时编辑区随键盘上移，
        // 光标 bring-into-view 才能在可见区域内生效（否则文字被键盘盖住）。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                ) { Text("取消") }
                Spacer(Modifier.weight(1f))
                Text(
                    "${text.length} 字",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSend,
                    enabled = canSend,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                ) { Text("发送") }
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                )
            )
        }
    }
}

/**
 * 模型返回的非图片文件经 FileProvider 调系统应用打开（intent 带原始扩展名推导的 MIME）；
 * 无应用可处理时 Toast 提示。仅 file:// 生成文件走此路径（远程链接维持可点击现状）。
 */
internal fun openGeneratedFile(context: Context, attachment: ChatAttachment) {
    val path = Uri.parse(attachment.uri).path ?: return
    try {
        val file = File(path)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (_: Exception) {
        // 路径不在 FileProvider 声明范围 / 无应用可打开等一律提示，不崩
        Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
    }
}
