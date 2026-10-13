package com.wavex.agent.feature.conversation.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.wavex.agent.core.ui.AgentTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * 抽屉会话条目长按操作弹窗回归：
 * - 长按唤起居中 Dialog（不是贴条目的 Popup 菜单），含置顶/归档/删除；
 * - 不含重命名项——重命名入口在每条会话右侧的编辑图标，弹窗里重复即冗余；
 * - 已置顶/已归档时文案翻转（取消置顶/取消归档），已归档与置顶互斥故无置顶行；
 * - 点删除回调 onDelete（真实交互里再走「删除对话」确认框，两级防误删）。
 */
class DrawerActionsDialogInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private var host: ComponentActivity? = null

    /** 按真实抽屉的装配方式挂一个条目：open 状态 = 弹窗开关，动作折叠成 lastEvent。 */
    private fun hostItem(
        title: String,
        pinned: Boolean = false,
        archived: Boolean = false,
    ): Pair<MutableState<Boolean>, MutableState<String?>> {
        val open = mutableStateOf(false)
        val lastEvent = mutableStateOf<String?>(null)
        val host = awaitResumedHost(compose)
        this.host = host
        compose.runOnUiThread {
            host.setContent {
                AgentTheme(darkTheme = false, dynamicColor = false) {
                    DrawerConversationItem(
                        title = title,
                        selected = false,
                        selectMode = false,
                        checked = false,
                        pinned = pinned,
                        archived = archived,
                        menuExpanded = open.value,
                        onMenuDismiss = { open.value = false; lastEvent.value = "dismiss" },
                        onClick = {},
                        onLongClick = { open.value = true; lastEvent.value = "longClick" },
                        onRename = { lastEvent.value = "rename" },
                        onTogglePin = { open.value = false; lastEvent.value = "pin" },
                        onToggleArchive = { open.value = false; lastEvent.value = "archive" },
                        onDelete = { open.value = false; lastEvent.value = "delete" }
                    )
                }
            }
        }
        return open to lastEvent
    }

    /**
     * 用完即 finish：残留的 RESUMED 宿主会让后续用例的 awaitResumedHost
     * 抓错 Activity（首次全绿、重跑失败正是这种排序运气型串扰）。
     */
    @After
    fun tearDown() {
        host?.let { h -> compose.runOnUiThread { h.finish() } }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnUiThread {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>()
                    .isEmpty()
            }
        }
    }

    @Test(timeout = 60_000)
    fun longPressOpensDialogWithoutRename() {
        dismissPrivacyDialog(instrumentation)
        val (open, lastEvent) = hostItem("长按会话")

        compose.onNodeWithText("长按会话").performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 5_000) { open.value }
        // 居中 Dialog 形态（不是 DropdownMenu 的 Popup）
        compose.onNode(isDialog()).assertExists().assertIsDisplayed()
        compose.onNodeWithText("置顶").assertExists()
        compose.onNodeWithText("归档").assertExists()
        compose.onNodeWithText("删除").assertExists()
        // 重命名入口在条目右侧图标，弹窗里不再重复
        compose.onNodeWithText("重命名").assertDoesNotExist()

        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { lastEvent.value == "delete" }
        compose.waitUntil(timeoutMillis = 5_000) { !open.value }
    }

    @Test(timeout = 60_000)
    fun pinnedFlipsToUnpinLabel() {
        dismissPrivacyDialog(instrumentation)
        val (open, lastEvent) = hostItem("已置顶会话", pinned = true)

        compose.onNodeWithText("已置顶会话").performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 5_000) { open.value }
        compose.onNodeWithText("取消置顶").assertExists()
        compose.onNodeWithText("置顶").assertDoesNotExist()

        compose.onNodeWithText("取消置顶").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { lastEvent.value == "pin" }
    }

    @Test(timeout = 60_000)
    fun archivedShowsUnarchiveAndHidesPinRow() {
        dismissPrivacyDialog(instrumentation)
        val (open, lastEvent) = hostItem("已归档会话", archived = true)

        compose.onNodeWithText("已归档会话").performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 5_000) { open.value }
        // 已归档与置顶互斥：无置顶行，归档文案翻转为取消归档
        compose.onNodeWithText("取消归档").assertExists()
        compose.onNodeWithText("置顶").assertDoesNotExist()
        compose.onNodeWithText("取消置顶").assertDoesNotExist()
        compose.onNodeWithText("归档").assertDoesNotExist()

        compose.onNodeWithText("取消归档").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { lastEvent.value == "archive" }
    }
}
