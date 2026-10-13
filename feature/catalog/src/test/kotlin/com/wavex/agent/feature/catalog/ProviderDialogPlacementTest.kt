package com.wavex.agent.feature.catalog

import com.wavex.agent.feature.catalog.ui.providerDialogTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务商弹窗键盘跟随（最小位移放置）的回归测试。
 *
 * 背景：旧实现 `align(Center) + imePadding()` 在矮屏手机上，键盘一弹就把整卡
 * 钉到屏幕最顶端（y=0、盖住状态栏）——用户报告「点击 API Key 时弹窗顶到手机
 * 顶部时弹一下」。平板（1200x1920@204）实测：卡片高 748px、IME 620px，旧实现
 * 卡片整体上移 309px（=IME/2），而键盘实际只压住卡片底边 34px。
 *
 * 不变式：
 * 1. 键盘顶边未触及卡片底部 → 卡片保持居中（top = (H-h)/2）；
 * 2. 触及后 → top = H-ime-h（底边恰在键盘上方，1:1 跟随，位移最小）；
 * 3. top ≥ 状态栏 inset（永不越过屏幕顶端）；
 * 4. 触点连续：ime = (H-h)/2 时两条规则给出同一 top。
 */
class ProviderDialogPlacementTest {

    /** 平板实测几何：窗口 1920、卡片 748、状态栏 50（px @ density 204） */
    private val h = 1920
    private val card = 748
    private val statusBar = 50

    @Test
    fun `no keyboard keeps card centered`() {
        assertEquals(586, providerDialogTop(h, card, imeBottom = 0, topInset = statusBar))
    }

    @Test
    fun `keyboard overlapping card bottom moves card by the overlap only`() {
        // 键盘顶边 1300，居中卡片底边 1334 → 只需上移 34px（旧实现移了 310px）
        assertEquals(552, providerDialogTop(h, card, imeBottom = 620, topInset = statusBar))
    }

    @Test
    fun `touch point is continuous with centered position`() {
        // ime = (H-h)/2 时键盘顶边恰好在卡片底边：两条规则应给出同一 top
        val touch = (h - card) / 2
        assertEquals((h - card) / 2, providerDialogTop(h, card, touch, statusBar))
    }

    @Test
    fun `capped card never rises above status bar`() {
        // 矮屏手机：卡片自然高 1504 > 可用 857 → 被封顶为 857；
        // 旧公式 (H-h-ime)/2 = (1632-918-714)/2 = 0（钉在 y=0 盖状态栏）
        assertEquals(61, providerDialogTop(1632, 857, imeBottom = 714, topInset = 61))
    }

    @Test
    fun `extreme ime clamps to status bar`() {
        assertEquals(statusBar, providerDialogTop(h, card, imeBottom = h + 500, topInset = statusBar))
    }

    @Test
    fun `invariants hold across geometry sweep`() {
        val heights = listOf(1280, 1632, 1920, 2400)
        val cards = listOf(400, 748, 900, 1504)
        val imes = listOf(0, 200, 500, 714, 1000, 1400)
        for (window in heights) for (cardH in cards) for (ime in imes) {
            // IME 高于整窗在现实中不可能（键盘不会超过屏幕），这种退化组合不参与不变式
            if (window - ime - statusBar <= 0) continue
            // 测量封顶：卡片不会比可用区更高
            val capped = minOf(cardH, maxOf(window - ime - statusBar, 0))
            val top = providerDialogTop(window, capped, ime, statusBar)
            assertTrue("top>=statusBar ($window,$cardH,$ime)", top >= statusBar)
            assertTrue("bottom above ime ($window,$cardH,$ime)", top + capped <= window - ime)
            // 未触键盘时必须仍居中
            val centered = (window - capped) / 2
            val expected = if (window - ime >= centered + capped) centered else window - ime - capped
            assertEquals("follow rule ($window,$cardH,$ime)", maxOf(statusBar, expected), top)
        }
    }
}
