package com.wavex.agent.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片附件判断的文件名快路径（isImageName）：它是 isImageAttachment 的第一道
 * 判定，用于让常见图片附件在组合路径上零 I/O（不再先走 contentResolver.getType
 * 的 binder IPC）。正则语义与既有 IMAGE_NAME_REGEX 完全一致，用测试钉住。
 */
class AttachmentImageNameTest {

    @Test
    fun `common image extensions match`() {
        listOf(
            "IMG_20240101.jpg",
            "photo.jpeg",
            "screenshot.png",
            "anim.webp",
            "chart.gif",
            "scan.bmp",
            "icon.svg",
        ).forEach { name ->
            assertTrue("expected image: $name", isImageName(name))
        }
    }

    @Test
    fun `uppercase extensions match`() {
        assertTrue(isImageName("IMG_0001.JPG"))
        assertTrue(isImageName("Photo.PNG"))
    }

    @Test
    fun `non-image extensions and missing extension do not match`() {
        listOf(
            "report.pdf",
            "notes.txt",
            "archive.zip",
            "no-extension",
            "camera_1728888888888",
        ).forEach { name ->
            assertFalse("expected non-image: $name", isImageName(name))
        }
    }

    @Test
    fun `image extension must be at the end`() {
        // a.jpg.bak 不是图片：正则锚定末尾（避免中名带图片后缀的文件误判）
        assertFalse(isImageName("a.jpg.bak"))
    }
}
