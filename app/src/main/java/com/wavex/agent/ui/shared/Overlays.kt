package com.wavex.agent.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wavex.agent.model.ChatAttachment
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp

@Composable
internal fun OverflowMenuRow(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color? = null) {
    val color = tint ?: MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, Modifier.size(19.dp), tint = color)
        Text(
            label,
            fontSize = 14.sp,
            color = color,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
internal fun ImageViewerDialog(
    attachment: ChatAttachment,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember(attachment.uri) { mutableStateOf(1f) }
        var offset by remember(attachment.uri) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val scope = rememberCoroutineScope()
        // 进行中的双击缩放动画：捏合开始时取消，避免两路写入同一状态互相拉扯
        var scaleAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        val transformState = androidx.compose.foundation.gestures.rememberTransformableState { zoomChange, panChange, _ ->
            scaleAnimJob?.cancel()
            scale = (scale * zoomChange).coerceIn(1f, 6f)
            offset = if (scale > 1f) offset + panChange else androidx.compose.ui.geometry.Offset.Zero
        }
        // 双击缩放：Google Photos 式补间（220ms FastOutSlowIn），不再硬跳变；
        // 双指捏合仍直接跟手（回调先取消本动画，无延迟）
        fun animateDoubleTapZoom() {
            val startScale = scale
            val startOffset = offset
            val targetScale = if (startScale > 1f) 1f else 2.5f
            val targetOffset = if (targetScale == 1f) androidx.compose.ui.geometry.Offset.Zero else startOffset
            scaleAnimJob = scope.launch {
                animate(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ) { progress, _ ->
                    scale = lerp(startScale, targetScale, progress)
                    offset = androidx.compose.ui.geometry.Offset(
                        lerp(startOffset.x, targetOffset.x, progress),
                        lerp(startOffset.y, targetOffset.y, progress)
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = { animateDoubleTapZoom() }
                    )
                }
                .transformable(transformState)
        ) {
            coil.compose.AsyncImage(
                model = attachment.uri,
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
            Text(
                attachment.name,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
            )
        }
    }
}

@Composable
internal fun TypingDots() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "typingPhase"
    )
    Row(modifier = Modifier.padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        // 相位错开 2π/3：正弦上下浮动；叠加 0.55–1 呼吸透明度
                        val wave = sin(phase - i * (2 * PI.toFloat() / 3f))
                        translationY = -3.dp.toPx() * wave
                        alpha = lerp(0.55f, 1f, (wave + 1f) / 2f)
                    }
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}
