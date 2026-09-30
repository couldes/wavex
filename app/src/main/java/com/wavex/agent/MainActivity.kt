package com.wavex.agent

import android.content.Intent
import androidx.activity.viewModels
import com.wavex.agent.state.WavexViewModel
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wavex.agent.ui.theme.AgentTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.wavex.agent.model.AgentConversation
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatMessage
import com.wavex.agent.model.ChatRequestMessage
import com.wavex.agent.model.TREE_ROOT
import com.wavex.agent.ui.drawer.DrawerWidth
import com.wavex.agent.ui.MainTab
import com.wavex.agent.ui.ThemeChoice
import com.wavex.agent.ui.REASONING_LEVELS
import com.wavex.agent.ui.shared.attachmentDisplayName
import com.wavex.agent.ui.AgentApp
import com.wavex.agent.data.ProviderStore
import com.wavex.agent.data.Provider
import com.wavex.agent.network.ApiClient
import com.wavex.agent.data.ConversationStore
import com.wavex.agent.data.AttachmentLoader
import com.wavex.agent.model.AgentConversationData
import com.wavex.agent.model.StoredMessage

// 思考等级："" = 不传参数（跟随模型默认）；极低/低/中/高一一对应 reasoning_effort
// 的 minimal/low/medium/high（gpt-5 系全部档位），纯中文短标签，排版整齐

* 精确贴底：把列表滚到内容真正的末尾（末条消息底边 == 视口底边）。
 * scrollToItem 的 offset 语义对“末条比视口矮”的场景不可靠（实测会回填上方 item，
 * 停在离底约一屏处），所以用“测量剩余距离 → 滚动”的收敛式：
 * 末条不可见先顶对齐它，再按实际剩余像素补滚（scrollBy 会被内容边界自然鈄住）。
 */


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
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgentApp(agentState) }
        handleSharedIntent(intent)
        splashScreen.setOnExitAnimationListener { provider ->
            // 内容从轻微下沉的位置回弹到原位，遮罩淡出（参考 Mihon，修复页面向下偏移的问题）。
            // 整个回调包 runCatching：MIUI 可能不走标准启动图标动画路径，此时
            // splashscreen 1.0.1 的 ViewImpl31.iconView 是 `platformView.iconView!!`，
            // 直接抛 NPE 连累主进程闪退（旧包 v0.13 实际发生过）；动画失败也不能挡住进入应用。
            var fadeStarted = false
            runCatching {
                val content = findViewById<android.view.View>(android.R.id.content)
                content.translationY = 16f * resources.displayMetrics.density
                content.animate()
                    .translationY(0f)
                    .setDuration(200L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                provider.iconView.translationY = 0f
                provider.view.animate()
                    .alpha(0f)
                    .setDuration(200L)
                    .withEndAction { runCatching { provider.remove() } }
                    .start()
                fadeStarted = true
            }
            // 动画没起来（iconView 为 null 等）：启动画面视图必须移除，否则会一直盖在内容上
            if (!fadeStarted) runCatching { provider.remove() }
        }
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

*/
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
*/

*/
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

* 底部让位：键盘弹出时贴住键盘上沿，关闭时为底部 Tab + 导航栏留位。
 * 在布局阶段读取 IME insets（不触发重组），键盘动画期间不会卡顿。
 */

* 抽屉拖拽手势，对标 EhViewer 的 DrawerLayout：
 * - Initial 阶段抢先处理，一旦判定为横向主导手势就立即锁定拦截；
 * - 纵向先主导则本次手势彻底放弃，不干扰列表滚动；
 * - 阈值仅为系统 touchSlop，非常灵敏。
 * 注意：进度面板本身在跟随手指移动，不能用控件本地坐标算速度，
 * 这里用净位移 + 时间戳自己估算速度。
 */

*/

*/

* 容器级「点按/滑动收起键盘」：Initial pass 观察。纯点按（无滚动/无长按）抬起时
 * 收起键盘；滑动越过 touchSlop（纵向主导）也**立即收起**（ChatGPT 式，每次手势一次）。
 *
 * 与逐处加 clickable 的区别：不用枚举所有可点控件，消息列表、代码块、表格、
 * 图片、附件条上点击都生效（它们没消费 down 时这里兜底）。
 * 不会误伤：按钮/气泡等子控件在 Main pass 消费 down/up（那是明确的子控件点击，
 * 不收键盘）；横向主导的滑动不收（抽屉拖拽有自己的收键盘+锁 IME 布局路径，
 * 不能抢在它前面收掉导致锁失效；代码块/表格横滚也不改输入意图），
 * 报备手势（输入胶囊/联网键）与选区激活照旧让路。
 * 报备通道见 keyboardSuppress：tapActive=完全不动（联网键/输入胶囊），
 * imeOnly=只收 IME 不清焦点（局部复制长按，清焦点会连选区一起清掉）。
 */
*/

* 报备本次手势不收键盘：down 置位、up 复位，容器观察器（dismissKeyboardOnTap）
 * 据此跳过收键盘。用于联网键（切换开关不想收键盘）与整个输入胶囊
 * （点输入框时容器会先收后由焦点弹开 → 键盘闪一下，报备后不再闪）。
 */

*/

* 输入区待发附件悬浮层（ChatGPT 式）：悬浮在消息列表底部，**不独立占一条**。
 * 旧实现是独立横条：附件卡背后多出一块与页面同色的白带，白带连同附件一起
 * 把聊天区顶上去一截（用户观感："白块也挡住气泡"）。悬浮后只有附件卡自身
 * 挡住其覆盖的气泡区域，周围气泡照常可见。
 * 图片 = 64dp 圆角缩略图；非图片 = 轻透信息卡（图标+文件名+格式）；
 * ✕ 删除无涟漪无动效（点击即删）。
 */

*/

* DeepSeek 式功能胶囊：圆角矩形底座 + 图标（可选文字），带状态高亮与
 * 长按 Toast 说明，点击热区大（40dp 高）。
 *
 * suppressKeyboardHide=true 时（联网开关）：在 Initial pass 报备本次手势
 * （keyboardSuppress.tapActive=true），容器的「点按收键盘」观察器据此跳过，
 * 点联网键不再收键盘；手势结束自动复位，不影响其他键。
 */

* 分支切换器（ChatGPT 式）：同一位置存在多个版本时显示 ‹ 2/3 ›，点击在分叉间切换。
 * wrap 内容宽度（可嵌在 End 对齐的操作行里，和复制按钮并排）。
 */

* 气泡交互：单击 = onTap（用户消息进编辑 / 模型消息清选区）；
 * 长按交给内部 SelectionContainer 直接进入系统文本选择（复制局部）。
 * 另在 Initial pass 放一个不消费事件的长按观察者：子级选中消费事件后
 * Main pass 的长按检测会取消，只有 Initial 能可靠观测到「长按了」。
 */

*/

* 附件缩略图（已发送附件条用）：图片直接显示（点击全屏查看）。
 */

* 用户消息下方的附件展示条：单行从右往左排（第一条贴齐右侧，与用户气泡同向），
 * 超出屏宽才横向滑动；图片 64dp 缩略图、音频/文档为信息卡（图标+文件名+格式）。
 * 已发送为只读（点图片可全屏查看）。
 */

* 流式光标（参考 Telegram / ChatGPT 光标）：亮度呼吸而非硬性灭亮 ——
 * 1→0.25 缓动往返，光标「常在」不消失，观感更稳。
 * 独立成组件，无限动画不牵连气泡其余部分重组；逐帧只更新 layer。
 */

* 思考过程区块（默认折叠）：摘要行显示「思考中…」/「已思考 N 字」，点击整块开合。
 * 思考期间不自动展开，只实时更新字数，避免打扰阅读。
 */

* 全屏图片查看器：单击关闭、双击缩放复位/放大、双指捏合缩放 + 拖动平移。
 * 用全屏 Dialog（黑底），底部显示文件名。
 */

* 等待首 token 的打字点（参考 ChatGPT / iMessage 输入指示器）：
 * 三个点按 1/3 周期相位错开的正弦上下浮动 + 呼吸，形成波浪感。
 * 全部动画值在 graphicsLayer 内读取，逐帧只更新 layer 不触发重组。
 */

* 「+ 添加服务商」入口：圆角胶囊 + 淡色主色调背景 + 描边。
 * 比纯文字 TextButton 更像按钮，用户一眼能认出是可点击的添加入口。
 */

* Mihon 式分段选择器：圆角容器 + 等宽选项，选中项用 primary 胶囊高亮。
 */
* 主题预览卡（Mihon 式）：卡内画一个迷你页面（状态栏/顶栏/两条消息/底部栏），
 * 选中态用 primary 描边 + 勾选角标，直观看到每种模式的实际观感。
 */
