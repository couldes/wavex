package com.wavex.agent.feature.conversation.ui

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavex.agent.core.ui.blockDescendantFling

/**
 * 底部输入条：文本框 + 附件/拍照/联网胶囊 + 发送⇄停止圆钮。
 *
 * 从 ChatScreen.kt 拆出（Task 7 Step 3：input/editor 单元分离）。原先它是
 * ChatScreen() 体内一段 175 行的 Column 字面量，与外层只通过值/回调耦合
 * （输入文本、是否生成中、附件有无、四个动作回调），因此可以整体提成
 * 无状态 composable —— 文本状态与发送逻辑仍由 ChatScreen 持有。
 *
 * 注意这里的两处「报备」不能丢：
 * - 容器 keyboardSuppressReport：点输入框时容器的收键盘观察器会先收起、
 *   焦点逻辑再弹开 → 键盘闪一下；报备后容器跳过，键盘状态由焦点自然控制。
 * - 发送钮同样报备，停止/发送动作不应改变键盘状态。
 */
@Composable
internal fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    isGenerating: Boolean,
    hasAttachments: Boolean,
    webSearch: Boolean,
    onToggleWebSearch: () -> Unit,
    onPickAttachments: () -> Unit,
    onLaunchCamera: () -> Unit,
    onExpandEditor: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp)
            .padding(top = 0.dp, bottom = 6.dp)
    ) {
        // 一体化输入条（ChatGPT/Gemini 式）：圆角胶囊容器内，上行是文本框，
        // 下行左侧附件/联网小图标、右侧发送圆钮。视觉上是一个整体，不再是裸露的控件拼盘。
        // keyboardSuppressReport：点输入框时容器的收键盘观察器会先收起、
        // 焦点逻辑再弹开 → 键盘闪一下；报备后容器跳过，键盘状态由焦点自然控制
        Surface(
            modifier = Modifier.fillMaxWidth().keyboardSuppressReport().blockDescendantFling(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
        ) {
            Column {
                // Box 容器：承载超长文本时右上角浮出的「放大编辑」入口
                Box(Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            // 输入框向上扩高（顶部内边距大、贴底近）：点击区域远离下方图标行，
                            // 也消除胶囊顶部的白色空隙感
                            .padding(top = 10.dp, bottom = 10.dp)
                            // 高度上限：超过约 7 行后停止长高、改为框内滚动。无上限时
                            // 超长输入会无限增高（输入区是非 weight 子项、先按全屏测量），
                            // 把消息列表挤没、输入框占满整屏——此 bug 曾回归过一次
                            .heightIn(min = 44.dp, max = 160.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                        ),
                        decorationBox = { inner ->
                            // 占位文字与真实输入区放进同一个 Box、同一套字体规格：
                            // 两者都在容器内垂直居中，光标与「输入消息…」逐像素对齐。
                            // （旧写法占位自己套 34dp 盒子居中、而输入区被 Compose 钉在顶部，
                            // 导致光标比占位文字高出几个 dp）
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (input.isEmpty()) {
                                    Text(
                                        "输入消息…",
                                        fontSize = 16.sp,
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                inner()
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        // 不设 onSend：回车键保持换行（聊天输入多行是常态）；
                        // 发送走右侧圆钮，避免单行 IME 动作吞掉换行
                    )
                    if (input.length >= EXPAND_EDIT_THRESHOLD) {
                        // 放大编辑入口：正文超过阈值后浮在右上角（点开全屏编辑器）。
                        // 不透明小圆底：避免与底下第一行文字叠在一起看不清
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 8.dp)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    CircleShape
                                )
                                .clickable(onClick = onExpandEditor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.OpenInFull,
                                contentDescription = "放大编辑",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 附件：DeepSeek 式胶囊钮（长按有 Toast 说明）
                    InputToolChip(
                        icon = Icons.Default.Add,
                        label = "附件",
                        contentDescription = "添加附件",
                        hint = "发送图片或文件给模型（支持多选）",
                        enabled = !isGenerating,
                        suppressKeyboardHide = true
                    ) { onPickAttachments() }
                    // 拍照：快门后返回，应用内大按钮确认（重拍/使用），不再用系统相机的小 ✓
                    InputToolChip(
                        icon = Icons.Default.PhotoCamera,
                        label = "拍照",
                        contentDescription = "拍照并作为附件",
                        hint = "拍照发给模型（拍照后可确认或重拍）",
                        enabled = !isGenerating,
                        suppressKeyboardHide = true
                    ) { onLaunchCamera() }
                    // 联网搜索：开启时胶囊高亮。点击前报备 keyboardSuppress：
                    // 容器的收键盘观察器在 Initial pass 看不到子控件消费（祖先先于后代），
                    // 会把这次点按当纯点按 —— 报备后它跳过，键盘保持打开，开关状态由
                    // 胶囊高亮即时反馈，用户可继续打字或直接发送
                    InputToolChip(
                        icon = Icons.Default.TravelExplore,
                        label = "联网",
                        contentDescription = if (webSearch) "关闭联网搜索" else "开启联网搜索",
                        hint = if (webSearch) "联网搜索已开启：回答前会先搜索最新网页" else "联网搜索已关闭：仅用模型自身知识回答",
                        active = webSearch,
                        enabled = !isGenerating,
                        suppressKeyboardHide = true
                    ) { onToggleWebSearch() }
                    Spacer(Modifier.weight(1f))
                    // 发送/停止：小圆钮，紧贴右下角。
                    // 参考 ChatGPT 发送⇄停止变形：背景色与图标都平滑过渡，
                    // 硬切改 Crossfade（150ms，足够快又不闪）
                    val sendActive = isGenerating || input.isNotBlank() || hasAttachments
                    val sendBg by animateColorAsState(
                        targetValue = if (sendActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        animationSpec = tween(150),
                        label = "sendBg"
                    )
                    Box(
                        Modifier
                            .keyboardSuppressReport()
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(sendBg, CircleShape)
                            .clickable {
                                if (isGenerating) {
                                    onStop()
                                } else {
                                    onSend()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Crossfade(
                            targetState = isGenerating,
                            animationSpec = tween(150),
                            label = "sendIcon"
                        ) { generating ->
                            Icon(
                                if (generating) Icons.Default.Stop else Icons.Default.ArrowUpward,
                                contentDescription = if (generating) "停止" else "发送",
                                Modifier.size(18.dp),
                                tint = if (sendActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
