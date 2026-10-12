package com.wavex.agent.feature.conversation.ui

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
class BottomFollowPolicy {
    private var initialized = false

    /**
     * 用户原地开合可折叠内容（如思考区）后的阅读意图：跟随被显式关掉，但此时
     * 几何上往往仍在贴底容差内 —— 若不抑制，onLayout 的重播种会在展开的头几帧
     * 把 shouldFollowBottom 洗回 true，纠偏把展开高度逐帧吃掉（回归过的 bug）。
     * 真实滚动（onUserScrolled/onUserScroll）或显式回底才解除：用户重新给出意图后，
     * 几何重播种恢复常态（滚回底部后流式跟随照常接手）。
     */
    private var readingIntent = false

    /** 是否已有任何意图写入（首帧播种 / 用户滚动 / 显式返回）。false = 全新会话首帧，可安全建立初始贴底意图。 */
    val isSeeded: Boolean get() = initialized

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
        // 阅读意图期间几何不洗写意图：原地展开的头几帧底边仍在容差内，
        // 若照常重播种，退出跟随会被立刻撤销。
        if (readingIntent) return
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
        readingIntent = false
        shouldFollowBottom = false
        refreshShowReturnToBottom()
    }

    /**
     * 原地开合可折叠内容（思考区）的阅读意图：退出贴底跟随，并抑制 onLayout 的
     * 几何重播种 —— 原地开合不产生滚动距离，几何上仍贴底，普通退出会在头几帧
     * 被洗掉（真机回归已复现）。由真实滚动或显式回底解除。
     */
    fun onReadingIntent() {
        initialized = true
        readingIntent = true
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
        readingIntent = false
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
        readingIntent = false
        shouldFollowBottom = false
        distanceFromBottomPx = unknownDistancePx
        lastScrollTowardBottom = true
        refreshShowReturnToBottom()
    }

    fun onReturnToBottom() {
        initialized = true
        readingIntent = false
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
