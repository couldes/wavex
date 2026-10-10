package com.wavex.agent.feature.conversation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wavex.agent.core.ui.bottomInsetClearance

/** 会话页输入栏底部让位：Tab 栏高度（壳层导航布局参数）注入 core:ui 通用实现。 */
@Composable
fun Modifier.bottomInputClearance(imeSettling: () -> Boolean = { false }): Modifier =
    bottomInsetClearance(bottomBarHeight = TabBarHeight, imeSettling = imeSettling)
