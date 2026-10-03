package com.wavex.agent.ui.chat

/**
 * Keeps the user's intent separate from transient list measurements.
 *
 * A growing last item can temporarily report as being below the viewport before the
 * following measure pass has applied the scroll correction. That must not be treated as
 * a user scroll. Only an explicit drag opts out of bottom-following.
 */
internal class BottomFollowPolicy {
    private var initialized = false

    var shouldFollowBottom: Boolean = true
        private set

    var showReturnToBottom: Boolean = false
        private set

    fun onLayout(atBottom: Boolean) {
        if (!initialized) {
            initialized = true
            // Seed from the existing per-conversation position once. Later growth must not
            // overwrite that intent with a transient measurement above the bottom.
            shouldFollowBottom = atBottom
        } else if (atBottom) {
            shouldFollowBottom = true
        }
        showReturnToBottom = !shouldFollowBottom
    }

    fun onContentChanged(): Boolean {
        showReturnToBottom = !shouldFollowBottom
        return shouldFollowBottom
    }

    fun onUserScroll() {
        initialized = true
        shouldFollowBottom = false
        showReturnToBottom = true
    }

    fun onReturnToBottom() {
        initialized = true
        shouldFollowBottom = true
        showReturnToBottom = false
    }
}
