package com.wavex.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomFollowPolicyTest {
    @Test
    fun `initial layout above bottom preserves the restored reading position`() {
        val policy = BottomFollowPolicy()

        policy.onLayout(atBottom = false)

        assertFalse(policy.shouldFollowBottom)
        assertTrue(policy.showReturnToBottom)
    }

    @Test
    fun `content growth keeps following when the previous layout was pinned`() {
        val policy = BottomFollowPolicy()

        policy.onLayout(atBottom = true)
        assertTrue(policy.onContentChanged())
    }

    @Test
    fun `later layout growth cannot cancel established bottom intent`() {
        val policy = BottomFollowPolicy()

        policy.onLayout(atBottom = true)
        policy.onLayout(atBottom = false)

        assertTrue(policy.shouldFollowBottom)
        assertFalse(policy.showReturnToBottom)
    }

    @Test
    fun `content growth does not steal the list after user scrolls away`() {
        val policy = BottomFollowPolicy()

        policy.onLayout(atBottom = true)
        policy.onUserScroll()

        assertFalse(policy.onContentChanged())
        assertTrue(policy.showReturnToBottom)
    }

    @Test
    fun `a later layout at bottom clears the return button`() {
        val policy = BottomFollowPolicy()

        policy.onUserScroll()
        policy.onLayout(atBottom = true)

        assertFalse(policy.showReturnToBottom)
        assertTrue(policy.shouldFollowBottom)
    }

    @Test
    fun `explicit return restores following before a fresh layout arrives`() {
        val policy = BottomFollowPolicy()
        policy.onUserScroll()

        policy.onReturnToBottom()
        policy.onLayout(atBottom = false)

        assertTrue(policy.onContentChanged())
        assertFalse(policy.showReturnToBottom)
    }
}
