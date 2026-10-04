package com.wavex.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomFollowPolicyTest {
    private fun policy(thresholdPx: Float = 1000f): BottomFollowPolicy =
        BottomFollowPolicy().apply { returnButtonThresholdPx = thresholdPx }

    @Test
    fun `initial layout above bottom preserves the restored reading position`() {
        val policy = policy()

        policy.onLayout(atBottom = false)

        assertFalse(policy.shouldFollowBottom)
        assertTrue(policy.showReturnToBottom)
    }

    @Test
    fun `content growth keeps following when the previous layout was pinned`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        assertTrue(policy.onContentChanged())
    }

    @Test
    fun `later layout growth cannot cancel established bottom intent`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onLayout(atBottom = false)

        assertTrue(policy.shouldFollowBottom)
        assertFalse(policy.showReturnToBottom)
    }

    @Test
    fun `a later layout at bottom clears the return button`() {
        val policy = policy()

        policy.onUserScroll()
        policy.onLayout(atBottom = true)

        assertFalse(policy.showReturnToBottom)
        assertTrue(policy.shouldFollowBottom)
    }

    @Test
    fun `explicit return restores following before a fresh layout arrives`() {
        val policy = policy()
        policy.onUserScroll()
        policy.onUserScrolled(1500f)

        policy.onReturnToBottom()
        policy.onLayout(atBottom = false)

        assertTrue(policy.onContentChanged())
        assertFalse(policy.showReturnToBottom)
    }

    @Test
    fun `drag start at the bottom does not flash the button`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onUserScroll()

        assertFalse(policy.showReturnToBottom)
    }

    @Test
    fun `a short scroll into history keeps the button hidden`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onUserScroll()
        policy.onUserScrolled(300f)

        assertFalse(policy.showReturnToBottom)
    }

    @Test
    fun `scrolling into history never shows the button, however far`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onUserScroll()
        policy.onUserScrolled(1500f)

        assertFalse(policy.showReturnToBottom)
    }

    @Test
    fun `heading back toward the bottom shows the button past the threshold`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onUserScroll()
        policy.onUserScrolled(1500f)
        policy.onUserScrolled(-300f)

        assertTrue(policy.showReturnToBottom)
    }

    @Test
    fun `heading back into history hides the button at once`() {
        val policy = policy()
        policy.onLayout(atBottom = true)
        policy.onUserScroll()
        policy.onUserScrolled(1500f)
        policy.onUserScrolled(-300f)
        assertTrue(policy.showReturnToBottom)

        policy.onUserScrolled(200f)

        assertFalse(policy.showReturnToBottom)
    }

    @Test
    fun `distance counts net travel from the bottom, not total flicks`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onUserScroll()
        policy.onUserScrolled(1500f)
        policy.onUserScrolled(-400f)
        assertTrue(policy.showReturnToBottom)

        policy.onUserScrolled(100f)
        assertFalse(policy.showReturnToBottom)
        policy.onUserScrolled(-100f)
        assertTrue(policy.showReturnToBottom)
    }

    @Test
    fun `reaching the bottom on layout resets the distance`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onUserScroll()
        policy.onUserScrolled(1500f)
        policy.onUserScrolled(-100f)
        assertTrue(policy.showReturnToBottom)

        policy.onLayout(atBottom = true)
        policy.onUserScrolled(-300f)

        assertFalse(policy.showReturnToBottom)
    }

    @Test
    fun `editing jump to an older message shows the button`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onJumpedAwayFromBottom()

        assertTrue(policy.showReturnToBottom)
    }

    @Test
    fun `viewport shrink re-evaluates visibility with the new threshold`() {
        val policy = policy(thresholdPx = 1000f)
        policy.onLayout(atBottom = true)
        policy.onUserScroll()
        policy.onUserScrolled(1200f)
        policy.onUserScrolled(-500f)
        policy.onLayout(atBottom = false)
        assertFalse(policy.showReturnToBottom)

        policy.returnButtonThresholdPx = 600f
        policy.onLayout(atBottom = false)

        assertTrue(policy.showReturnToBottom)
    }

    @Test
    fun `content growth does not steal the list after user scrolls away`() {
        val policy = policy()

        policy.onLayout(atBottom = true)
        policy.onUserScroll()
        policy.onUserScrolled(1500f)

        assertFalse(policy.onContentChanged())
        assertFalse(policy.showReturnToBottom)
    }
}
