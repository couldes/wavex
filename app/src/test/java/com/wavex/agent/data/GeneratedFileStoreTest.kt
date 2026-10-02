package com.wavex.agent.data

import com.wavex.agent.engine.ResponseImageExtractor.WriteOutcome.Failed
import com.wavex.agent.engine.ResponseImageExtractor.WriteOutcome.Saved
import com.wavex.agent.engine.ResponseImageExtractor.WriteOutcome.TooLarge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * 行为钉子：生成文件落盘（UUID 命名、超限先拒不解码、坏 base64 拒收）
 * 与孤儿清理（只删未引用文件）。
 */
class GeneratedFileStoreTest {

    private fun tmpDir(): File = createTempDir(prefix = "gfs-test").also { it.deleteOnExit() }

    private val b64 = Base64.getEncoder().encodeToString("hello 图片".toByteArray())

    @Test
    fun `write decodes and persists as uuid ext`() {
        val dir = tmpDir()
        val outcome = GeneratedFileStore.write(dir, "image/png", b64)
        assertTrue(outcome is Saved)
        val file = File((outcome as Saved).uri.removePrefix("file://"))
        assertEquals(dir, file.parentFile)
        assertTrue(file.name.endsWith(".png"))
        assertTrue(file.name.removeSuffix(".png").length == 36) // UUID
        assertArrayEquals("hello 图片".toByteArray(), file.readBytes())
    }

    @Test
    fun `extFor table and fallbacks`() {
        assertEquals(
            "xlsx",
            GeneratedFileStore.extFor("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        )
        assertEquals("svg", GeneratedFileStore.extFor("image/svg+xml"))
        assertEquals("bin", GeneratedFileStore.extFor("application/vnd.ms-excel"))
        assertEquals("bin", GeneratedFileStore.extFor("weird"))
    }

    @Test
    fun `oversize rejected without decoding or writing`() {
        val dir = tmpDir()
        // 解码后 > 15MB 的全 'A' 串：长度 > MAX*4/3（'A' 是合法 base64 字符，能解码但先估尺寸拒绝）
        val huge = "A".repeat(GeneratedFileStore.MAX_FILE_BYTES * 4 / 3 + 16)
        val outcome = GeneratedFileStore.write(dir, "image/png", huge)
        assertTrue(outcome is TooLarge)
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `invalid base64 yields failed and no file`() {
        val dir = tmpDir()
        val outcome = GeneratedFileStore.write(dir, "image/png", "!!!")
        assertTrue(outcome is Failed)
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `sweepOrphans deletes only unreferenced files`() {
        val dir = tmpDir()
        val kept = File(dir, "aaa.png").apply { writeBytes(byteArrayOf(1)) }
        File(dir, "bbb.png").writeBytes(byteArrayOf(2))
        File(dir, "ccc.png").writeBytes(byteArrayOf(3))
        GeneratedFileStore.sweepOrphans(dir, setOf("file://" + kept.absolutePath))
        assertTrue(kept.exists())
        assertEquals(1, dir.listFiles()?.size)
    }

    @Test
    fun `sweepOrphans tolerates missing dir`() {
        GeneratedFileStore.sweepOrphans(File(tmpDir(), "nonexistent"), emptySet())
        assertNull(null) // 不抛即通过
    }
}
