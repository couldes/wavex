package com.wavex.agent.core.data

import com.wavex.agent.core.data.attachment.IntakeAttachmentStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 行为钉子：用户附件入口快照落盘（UUID 命名、扩展名规范化、失败不残盘）
 * 与孤儿清理（只删未引用文件）。背景：附件曾只存外部 content:// uri，
 * 重启/授权失效后历史重建全部读失败（「无法读取…文件可能已被移动或删除」）。
 */
class IntakeAttachmentStoreTest {

    private fun tmpDir(): File = createTempDir(prefix = "ias-test").also { it.deleteOnExit() }

    @Test
    fun `copy persists bytes as uuid ext and returns file uri`() {
        val dir = tmpDir()
        val bytes = "PDF 内容样例 📄".toByteArray()
        val uri = IntakeAttachmentStore(dir).copy(bytes.inputStream(), "pdf")

        assertNotNull(uri)
        val file = File(uri!!.removePrefix("file://"))
        assertEquals(dir, file.parentFile)
        assertTrue(file.name.endsWith(".pdf"))
        assertTrue(file.name.removeSuffix(".pdf").length == 36) // UUID
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `ext normalization`() {
        assertEquals("pdf", IntakeAttachmentStore.extFor("pdf"))
        assertEquals("pdf", IntakeAttachmentStore.extFor("PDF"))
        assertEquals("bin", IntakeAttachmentStore.extFor(""))
        // 非字母数字字符被滤掉；滤空回落 bin
        assertEquals("gz", IntakeAttachmentStore.extFor("g.z"))
        assertEquals("bin", IntakeAttachmentStore.extFor("。￥！"))
        // 超长截断到 8 位
        assertEquals(8, IntakeAttachmentStore.extFor("abcdefghijk").length)
    }

    @Test
    fun `copy failure yields null and no partial file`() {
        val dir = tmpDir()
        // read 必抛的坏流
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("boom")
        }
        val uri = IntakeAttachmentStore(dir).copy(broken, "pdf")

        assertNull(uri)
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `sweepOrphans deletes only unreferenced files`() {
        val dir = tmpDir()
        val store = IntakeAttachmentStore(dir)
        val kept = File(dir, "11111111-1111-1111-1111-111111111111.pdf").apply { writeBytes(byteArrayOf(1)) }
        val dropped = File(dir, "22222222-2222-2222-2222-222222222222.pdf").apply { writeBytes(byteArrayOf(2)) }
        val foreign = File(dir.parentFile, "outside.pdf").apply { writeBytes(byteArrayOf(3)) }

        store.sweepOrphans(setOf("file://${kept.absolutePath}"))

        assertTrue(kept.exists())
        assertFalse(dropped.exists())
        assertTrue(foreign.exists()) // 目录外文件不受影响
    }

    @Test
    fun `missing dir sweeps silently`() {
        IntakeAttachmentStore(File(tmpDir(), "nope")).sweepOrphans(emptySet()) // 不抛即通过
    }
}
