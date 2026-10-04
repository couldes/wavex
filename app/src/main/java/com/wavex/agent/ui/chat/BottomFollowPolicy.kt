package com.wavex.agent.ui.chat

/**
 * Keeps the user's intent separate from transient list measurements.
 *
 * A growing last item can temporarily report as being below the viewport before the
 * following measure pass has applied the scroll correction. That must not be treated as
 * a user scroll. Only an explicit drag opts out of bottom-following.
 *
 * The "回到底部" button additionally obeys two user-facing rules:
 *  - **Distance**: the button appears only after the net scroll travel away from the
 *    bottom exceeds [returnButtonThresholdPx] (one viewport). A flick near the bottom
 *    must not summon it.
 *  - **Direction**: the button shows only while the latest user scroll heads BACK
 *    toward the bottom. Scrolling up into history never shows it — reading older
 *    messages must not be interrupted — and one flick into history hides it at once.
 */
internal class BottomFollowPolicy {
    private var initialized = false

    var shouldFollowBottom: Boolean = true
        private set

    var showReturnToBottom: Boolean = false
        private set

    /** Viewport height in px; the UI refreshes it every frame (keyboard changes it). */
    var returnButtonThresholdPx: Float = 0f

    /** Large sentinel for positions whose true distance is unknown (restored/jumped). */
    private val unknownDistancePx = Float.MAX_VALUE / 4f

    /** Net pixel travel since the bottom was last pinned; >= 0. */
    private var distanceFromBottomPx = 0f

    /** Direction of the latest user scroll: true = back toward the bottom. */
    private var lastScrollTowardBottom = true

    fun onLayout(atBottom: Boolean) {
        if (!initialized) {
            initialized = true
            // Seed from the existing per-conversation position once. Later growth must not
            // overwrite that intent with a transient measurement above the bottom.
            shouldFollowBottom = atBottom
            if (!atBottom) {
                // Restored mid-history with no scroll observation yet: treat as far away
                // so the return button still offers itself (previous behavior).
                distanceFromBottomPx = unknownDistancePx
            }
        } else if (atBottom) {
            shouldFollowBottom = true
            distanceFromBottomPx = 0f
        }
        refreshShowReturnToBottom()
    }

    fun onContentChanged(): Boolean {
        refreshShowReturnToBottom()
        return shouldFollowBottom
    }

    fun onUserScroll() {
        initialized = true
        shouldFollowBottom = false
        refreshShowReturnToBottom()
    }

    /**
     * One frame of consumed scroll. Positive = into history (away from the bottom),
     * negative = back toward the bottom. Called for drags and flings; clamped at 0 so
     * the bottom acts as the exact zero point.
     */
    fun onUserScrolled(deltaPx: Float) {
        if (deltaPx == 0f) return
        initialized = true
        lastScrollTowardBottom = deltaPx < 0f
        distanceFromBottomPx = (distanceFromBottomPx + deltaPx).coerceAtLeast(0f)
        refreshShowReturnToBottom()
    }

    /**
     * An explicit programmatic jump away from the bottom (e.g. editing an older
     * message). The real distance is unknown, so it counts as far.
     */
    fun onJumpedAwayFromBottom() {
        initialized = true
        shouldFollowBottom = false
        distanceFromBottomPx = unknownDistancePx
        lastScrollTowardBottom = true
        refreshShowReturnToBottom()
    }

    fun onReturnToBottom() {
        initialized = true
        shouldFollowBottom = true
        distanceFromBottomPx = 0f
        refreshShowReturnToBottom()
    }

    private fun refreshShowReturnToBottom() {
        showReturnToBottom = !shouldFollowBottom &&
            distanceFromBottomPx > returnButtonThresholdPx &&
            lastScrollTowardBottom
    }
}
