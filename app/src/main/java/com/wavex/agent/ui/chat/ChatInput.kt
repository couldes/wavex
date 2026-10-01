package com.wavex.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.model.ChatAttachment
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Surface
import android.widget.Toast
import androidx.core.content.FileProvider
import com.wavex.agent.ui.shared.isImageAttachment
import com.wavex.agent.ui.shared.attachmentExtLabel
import com.wavex.agent.ui.shared.attachmentDisplayName
import com.wavex.agent.ui.shared.AUDIO_EXT_SET
import com.wavex.agent.ui.chat.keyboardSuppressReport

@Composable
internal fun PendingAttachmentsOverlay(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    onImageClick: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        attachments.forEach { attachment ->
            val isImage = remember(attachment.uri, attachment.name) { isImageAttachment(context, attachment) }
            if (isImage) {                // 图片与删除标志包进同一个 Box：✕ 叠在图片右上角（此前两者是
                // Row 兄弟节点，✕ 掉到图片右侧去了，这是 bug）
                Box {
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(attachment.uri)
                            .size(256)
                            .build(),
                        contentDescription = attachment.name,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onImageClick(attachment) },
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    // 删除标志：黑色半透明圆底 + 白 ✕（无涟漪，叠右上角）
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onRemove(attachment) }
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除附件",
                            modifier = Modifier.size(13.dp),
                            tint = Color.White
                        )
                    }
                }
            } else {
                val ext = remember(attachment.uri, attachment.name) { attachmentExtLabel(context, attachment) }
                val isAudio = ext in AUDIO_EXT_SET
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    // 实心底：浮层背后是聊天气泡，半透明底会透出文字（无投影）
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(64.dp)
                ) {
                    Row(
                        Modifier.padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = if (isAudio) "音频附件" else "文档附件",
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                attachmentDisplayName(attachment.name),
                                maxLines = 1,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 130.dp)
                            )
                            Text(
                                if (isAudio) "音频 · $ext" else ext,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onRemove(attachment) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "删除附件", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** 按附件 id 从待发列表里删除（同一文件可添加多次，id 保证删哪个删哪个） */
internal fun removePendingAttachment(attachments: MutableList<ChatAttachment>, target: ChatAttachment) {
    attachments.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }?.let { attachments.removeAt(it) }
}

@Composable
internal fun rememberCameraLauncher(onCaptured: (android.net.Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    // rememberSaveable：旋转等配置变更会重建 Activity（Manifest 已删 configChanges），
    // 普通 remember 丢 URI 会让拍照结果静默落空（照片拍了却进不了附件）。
    // 保存的是路径字符串（File 不可 Saveable），用时可重建 File
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { ok ->
        val path = pendingPhotoPath
        pendingPhotoPath = null
        if (ok && path != null) {
            onCaptured(FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", java.io.File(path)
            ))
        } else if (!ok && path != null) {
            // 用户取消拍照/拍摄失败：删掉空壳文件，避免 camera/ 目录无限膨胀
            runCatching { java.io.File(path).delete() }
        }
    }
    return launch@{
        // 父目录必须先存在（相机进程只创建文件不建目录）；mkdirs 对已存在目录
        // 只是一次 stat，同步执行避免「后台还没建好目录 → 相机写文件失败」的竞态。
        // 文件本身由相机进程创建，这里不预创建
        val photoFile = java.io.File(
            java.io.File(context.filesDir, "camera"),
            "camera_${System.currentTimeMillis()}.jpg"
        )
        photoFile.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", photoFile)
        pendingPhotoPath = photoFile.absolutePath
        try {
            launcher.launch(uri)
        } catch (_: Exception) {
            pendingPhotoPath = null
            Toast.makeText(context, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun InputToolChip(
    icon: ImageVector,
    label: String? = null,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    hint: String? = null,
    suppressKeyboardHide: Boolean = false,
    onClick: () -> Unit
) {
    val hint = hint ?: contentDescription
    val context = LocalContext.current
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(11.dp))
            .then(if (suppressKeyboardHide) Modifier.keyboardSuppressReport() else Modifier)
            .background(
                when {
                    !enabled -> Color.Transparent
                    active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                }
            )
            .combinedClickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = { Toast.makeText(context, hint, Toast.LENGTH_SHORT).show() }
            )
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            Modifier.size(19.dp),
            tint = when {
                active -> MaterialTheme.colorScheme.primary
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            }
        )
        if (label != null) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
