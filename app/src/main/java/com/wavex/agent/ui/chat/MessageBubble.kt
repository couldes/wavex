package com.wavex.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatMessage
import kotlin.math.abs
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import android.widget.Toast
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.rememberUpdatedState
import com.wavex.agent.MarkdownText
import com.wavex.agent.blockBringIntoView
import com.wavex.agent.ui.chat.keyboardSuppress
import com.wavex.agent.ui.shared.TypingDots
import com.wavex.agent.ui.shared.isImageAttachment
import com.wavex.agent.ui.shared.attachmentExtLabel
import com.wavex.agent.ui.shared.AUDIO_EXT_SET
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun Modifier.bubbleClickable(
    onTap: () -> Unit,
    onLongPressObserve: () -> Unit
): Modifier {
    // 无涟漪无按压反馈（干净点击）
    val interactionSource = remember { MutableInteractionSource() }
    // 回调经 rememberUpdatedState 传给 pointerInput(Unit)：长按触发后的重组
    // 不会重启手势检测（旧实现以 lambda 为 key，长按那次重组会中途重启检测器）。
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPressObserve by rememberUpdatedState(onLongPressObserve)
    return this
        .combinedClickable(
            onClick = { currentOnTap() },
            interactionSource = interactionSource,
            indication = null
        )
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var imeReported = false
                try {
                    var longPressed = false
                    var moved = false
                    // 阶段一：Initial pass 观测（不消费），判定是否纯长按。
                    // 关键：长按成立必须用 withTimeoutOrNull 判定（与系统选词的
                    // awaitLongPressOrCancellation 同机制），不能靠等下一个事件的时间戳
                    // —— 手指完全静止时，超时后到抬起前可能一个事件都不发（up 就是
                    // 第一个事件），等事件会把标志位置位拖到抬起之后，根观察器（祖先、
                    // Initial pass 先于本节点看到 up）那时仍走 clearFocus → 选区消失。
                    val movedBeforeTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull true
                            if (!change.pressed) return@withTimeoutOrNull true // 超时前抬起：普通点按
                            val dx = abs(change.position.x - down.position.x)
                            val dy = abs(change.position.y - down.position.y)
                            if (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop) {
                                return@withTimeoutOrNull true // 超时前移出 slop：滚动/拖拽
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") true
                    }
                    if (movedBeforeTimeout == null) {
                        // 超时未被取消 → 长按成立（与系统选词同一时刻，无事件也成立）
                        longPressed = true
                        currentOnLongPressObserve() // 仅观测：不消费，选区由内部 SelectionContainer 自己接管
                        // imeOnly 报备必须在长按成立的那一刻置位（不能等抬起时）：
                        // 根观察器（祖先）在 Initial pass 先于本节点看到抬起，那时若
                        // imeOnly 为 false 会走 clearFocus → 焦点级联 → 选区容器失焦 →
                        // SelectionManager.onRelease → 选区消失。
                        keyboardSuppress.imeOnly = true
                        imeReported = true
                        // 长按成立后继续观测移动：拖出 slop = 拖选区/拖手柄，抬起交给选区系统
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = abs(change.position.x - down.position.x)
                            val dy = abs(change.position.y - down.position.y)
                            if (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop) {
                                moved = true
                                break
                            }
                        }
                    }
                    // 阶段二：纯长按（未拖动）的抬起，在 Main pass 吞掉。
                    // 关键顺序：Main pass 是后代先于祖先 —— 选区系统（气泡内部）先处理完抬起
                    // 保留选区；随后本修饰符消费抬起，阻止 combinedClickable 与
                    // LazyColumn 的点按检测把它判成点击（onTap → clearTextSelection 会清选区）。
                    if (longPressed && !moved) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                    }
                } finally {
                    // 手势结束（抬起/取消/异常）一律复位，避免标志卡住导致后续手势不收焦点
                    if (imeReported) keyboardSuppress.imeOnly = false
                }
            }
        }
}

@Composable
internal fun InlineMessageEditor(
    initialText: String,
    onCancel: () -> Unit,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth(0.92f)
    ) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 8,
                shape = RoundedCornerShape(14.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { if (text.isNotBlank()) onSend(text) },
                    enabled = text.isNotBlank(),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                ) { Text("发送") }
            }
        }
    }
}

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    streaming: Boolean = false,
    // 单击：用户消息进编辑，模型消息清选区；长按（观察）标记选区可能激活
    onTap: () -> Unit,
    onLongPressObserve: () -> Unit
) {
    // 系统提示/报错：居中小卡片样式，带图标与「复制错误信息」入口，
    // 与模型回复明显区分（不再像“模型回了两条”），也不在卡下另起一行复制钮。
    // 仍留在消息记录里：可回看、可复制，返回键/点空白可清选区。
    if (message.isError && message.fromUser == false) {
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .blockBringIntoView()
                    .bubbleClickable(onTap = onTap, onLongPressObserve = onLongPressObserve)
            ) {
                Row(
                    Modifier.padding(start = 11.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        message.text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(2.dp))
                    // 复制收进卡片右侧（垂直居中），不再孤零零挂在气泡下方
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                clipboard.setText(AnnotatedString(message.text))
                                Toast.makeText(context, "已复制错误信息", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制错误信息",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        return
    }
    if (message.fromUser) {
        // 用户消息：右对齐窄气泡（不占满宽度，保留对话感）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .blockBringIntoView()
                    .bubbleClickable(onTap = onTap, onLongPressObserve = onLongPressObserve)
            ) {
                BubbleContent(message, streaming, onTap)
            }
        }
    } else {
        // 模型消息：头像单独一行，气泡在头像下方铺满整行 —— 长代码块/表格
        // 可用到整行宽度（旧布局头像常驻左侧，挤掉 38dp 且代码块/表格被迫换行）
        Column(Modifier.fillMaxWidth()) {
            Surface(
                Modifier.size(30.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 气泡宽度自适应内容：短回复（如「你好！」）不再撑满整行；
            // 含代码块/表格/多行长内容时才铺满（代码块需要整行宽度横向滚动）。
            // 等待首 token 的占位阶段：收成窄胶囊（宽度包住打字点）
            val placeholderPhase = streaming && message.text.isBlank() && message.reasoning.isBlank()
            val needsFullWidth = !placeholderPhase && (
                message.text.contains("```") ||
                    message.text.contains("\n|") || message.text.trimStart().startsWith("|") ||
                    message.text.length > 160
                )
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .then(
                        when {
                            placeholderPhase -> Modifier.widthIn(min = 64.dp)
                            needsFullWidth -> Modifier.fillMaxWidth()
                            else -> Modifier
                        }
                    )
                    .blockBringIntoView()
                    .bubbleClickable(onTap = onTap, onLongPressObserve = onLongPressObserve)
            ) {
                BubbleContent(message, streaming, onTap)
            }
        }
    }
}

@Composable
internal fun BubbleContent(
    message: ChatMessage,
    streaming: Boolean,
    onTap: () -> Unit
) {
    Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        // 思考过程（可折叠）：思考阶段自动展开实时显示，正文开始后自动收起成一行摘要
        if (message.reasoning.isNotBlank()) {
            ReasoningBlock(message = message, streaming = streaming)
            Spacer(Modifier.height(6.dp))
        }
        if (streaming && message.text.isBlank() && message.reasoning.isBlank()) {
            // 等待首个 token：三个跳动的小点（思考增量到达后切换为思考区块）
            TypingDots()
        } else {
            // 所有消息常态可长按选中（复制局部）
            val content: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.Bottom) {
                    val textColor = when {
                        message.isError -> MaterialTheme.colorScheme.onErrorContainer
                        message.fromUser -> Color.White
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    if (message.fromUser || message.isError) {
                        Text(
                            message.text,
                            color = textColor,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    } else {
                        // 助手消息用 Markdown 渲染（代码块/表格/加粗/标题/列表…）；
                        // weight(1f, fill=false)：非流式时不强制铺满，MarkdownText 内容自适应宽度；
                        // 代码块标题栏的复制按钮回调在这里接剪贴板
                        MarkdownText(
                            message.text,
                            color = textColor,
                            modifier = Modifier.weight(1f, fill = false),
                            onCopyCode = { code ->
                                clipboard.setText(AnnotatedString(code))
                                Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
            // 所有消息常态可长按选中（复制局部）；取消选区走焦点级联（见 clearTextSelection），
            // 不再重建容器。LazyColumn 只组合可见项，屏外气泡不注册选区，无滚动性能负担。
            SelectionContainer { content() }
            if (streaming) {
                // 独立 composable：无限动画只在自身范围内重组，不牵连整个气泡
                StreamingCursor()
            }
        }
    }
}

@Composable
internal fun AttachmentThumbnail(
    attachment: ChatAttachment,
    onImageClick: (ChatAttachment) -> Unit
) {
    val context = LocalContext.current
    coil.compose.AsyncImage(
        model = coil.request.ImageRequest.Builder(context)
            .data(attachment.uri)
            .size(256) // 降采样：72dp 预览用不到原图，多图不再解码超时/内存抖动
            .build(),
        contentDescription = attachment.name,
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onImageClick(attachment) },
        contentScale = androidx.compose.ui.layout.ContentScale.Crop
    )
}

@Composable
internal fun MessageAttachmentsRow(
    attachments: List<ChatAttachment>,
    onImageClick: (ChatAttachment) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // 从右往左排：列表反转后渲染（第一条在右端、贴齐上方气泡），新附件向左累加；
    // 内容超出屏宽时自动滚到右端（第一条附件在可视区内，紧邻气泡正下方）。
    // 之前只读一次 maxValue（常为 0）且用 scrollTo 会打断手势；改为内容变宽后
    // 快照流观察到并轻推到右端，仍在滚动/拖动时不打扰
    LaunchedEffect(attachments) {
        snapshotFlow { scrollState.maxValue }.collectLatest { max ->
            if (max > 0 && !scrollState.isScrollInProgress) {
                delay(50)
                scrollState.scrollTo(max)
            }
        }
    }
    Row(
        Modifier
            // 必须铺满整行：Arrangement.End 才能把附件推到右端（从右往左排）
            .fillMaxWidth()
            // 溢出才挂 horizontalScroll，5 张以内必然不溢出（尺寸有上限）
            .then(if (attachments.size > 5) Modifier.horizontalScroll(scrollState) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.Bottom
    ) {
        attachments.asReversed().forEach { attachment ->
            if (remember(attachment.uri, attachment.name) { isImageAttachment(context, attachment) }) {
                AttachmentThumbnail(
                    attachment = attachment,
                    onImageClick = onImageClick
                )
            } else {
                val ext = remember(attachment.uri, attachment.name) { attachmentExtLabel(context, attachment) }
                val isAudio = ext in AUDIO_EXT_SET
                // 非图片信息卡：恢复原 Surface 轻透底色
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    modifier = Modifier.height(64.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = if (isAudio) "音频附件" else "文档附件",
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Text(
                                attachment.name,
                                maxLines = 1,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                            Text(
                                if (isAudio) "音频 · $ext" else ext,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StreamingCursor() {
    val alpha by androidx.compose.animation.core.rememberInfiniteTransition(label = "cursor")
        .animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(530, easing = FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "cursorAlpha"
        )
    Box(
        Modifier
            .padding(start = 2.dp)
            .width(3.dp)
            .height(17.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp))
            // 逐帧只更新 layer，不再每帧重组本组件（读 alpha 状态延迟到绘制阶段）
            .graphicsLayer { this.alpha = alpha }
    )
}

@Composable
internal fun ReasoningBlock(message: ChatMessage, streaming: Boolean) {
    val thinking = streaming && message.text.isBlank()
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(
        Modifier
            // 铺满气泡内容宽度：摘要行里 weight(1f) 的文字会把块撑到最大可用
            // 宽度，左右缘与外层气泡/正文对齐。此前用 widthIn(max=340dp) 封顶，
            // 气泡内容宽超过 340dp 时（宽屏/长回复/代码块/平板横屏）思考块
            // 右缘与气泡右缘之间会留出一段缺口（正文却铺满整行）。
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // 箭头旋转动画值（只在展开状态翻转时重组一次，不再 0°/180° 硬切）
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "chevronRotation"
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                if (thinking) "思考中… ${message.reasoning.length} 字" else "已思考 ${message.reasoning.length} 字",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(start = 5.dp)
                    .weight(1f)
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 展开收起：纵向展开/收起 + 淡入淡出（ChatGPT「已思考」折叠区同款）。
        // 旧实现 if(expanded) 硬切：内容突然出现/消失带得整个气泡高度跳变
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
        ) {
            // 思考内容同样走 Markdown 解析：模型思考里常带 **/##/列表等标记，
            // 纯文本渲染会满屏符号；流式期间 remember+LruCache 已控制开销
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            MarkdownText(
                text = message.reasoning,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onCopyCode = { code ->
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
