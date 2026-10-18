package com.wavex.agent.feature.conversation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户气泡选区配色的钉子。
 *
 * 用户气泡底色是 primary（蓝）、文字是白色，M3 默认选区色派生自 primary 会完全隐形，
 * 所以这里单独指定：手柄=浅白蓝，高亮=奶白。高亮必须保持半透明，否则白字被奶白盖住。
 */
class UserBubbleSelectionColorsTest {

    @Test
    fun `handle is a pale white blue`() {
        val handle = userBubbleSelectionColors.handleColor

        // 「浅」= 每个通道都不低于 0.8，整体接近白；蓝是最强通道（带蓝调而非纯白）
        assertTrue("pale: every channel >= 0.8, got $handle",
            handle.red >= 0.8f && handle.green >= 0.8f && handle.blue >= 0.8f)
        assertTrue("blue is the dominant channel: $handle", handle.blue > handle.red)
        assertTrue("blue at least as strong as green: $handle", handle.blue >= handle.green)
        assertEquals("handle must stay opaque", 1f, handle.alpha, 0.001f)
    }

    @Test
    fun `highlight is milky white and translucent enough to keep white text readable`() {
        val highlight = userBubbleSelectionColors.backgroundColor

        assertTrue("near white: $highlight", highlight.red > 0.9f && highlight.green > 0.9f)
        assertTrue("warm milky tint, red not below blue: $highlight", highlight.red >= highlight.blue)
        assertTrue("translucent: $highlight", highlight.alpha in 0.2f..0.5f)
    }
}
