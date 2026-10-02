package com.wavex.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 行为钉子：附件保存的纯函数部分。
 * 文件名清理（剥路径/非法字符替换/缺扩展名按 MIME 补全/空主名兜底）
 * 与保存目标 MIME 推导（http URL 扩展名、data URL、未知扩展名返回 null）。
 * MediaStore/网络写入依赖 Context，走真机验证，不在 JVM 测试范围。
 */
class AttachmentSaverTest {

    // ---- sanitizeFileName：显示名 → 保存文件名 ----

    @Test
    fun `legal name passes through unchanged`() {
        assertEquals("生成图片-1.png", AttachmentSaver.sanitizeFileName("生成图片-1.png", "image/png"))
    }

    @Test
    fun `missing extension is appended from mime`() {
        assertEquals(
            "数据表.xlsx",
            AttachmentSaver.sanitizeFileName("数据表", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        )
        assertEquals("photo.png", AttachmentSaver.sanitizeFileName("photo", "image/png"))
    }

    @Test
    fun `filesystem illegal chars are replaced with underscore`() {
        // '/' 是路径分隔符（先剥路径段），其余非法字符替换为 _
        assertEquals(
            "a_b_c_d_e_f.pdf",
            AttachmentSaver.sanitizeFileName("a\\b:c|d*e?f.pdf", "application/pdf")
        )
        assertEquals(
            "引用_处理_结果.png",
            AttachmentSaver.sanitizeFileName("引用<处理\"结果.png", "image/png")
        )
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals("report.pdf", AttachmentSaver.sanitizeFileName("  report.pdf  ", "application/pdf"))
    }

    @Test
    fun `blank stem gets fallback prefix`() {
        assertEquals("附件.png", AttachmentSaver.sanitizeFileName(".png", "image/png"))
        assertEquals("附件.png", AttachmentSaver.sanitizeFileName("/", "image/png"))
    }

    @Test
    fun `extension kept even when mime differs`() {
        // 用户/模型给的显示名扩展名优先于 MIME 推导（尊重原扩展名，仅缺省时补）
        assertEquals("img.jpg", AttachmentSaver.sanitizeFileName("img.jpg", "image/png"))
    }

    // ---- mimeFromUri：保存时源 MIME 推导 ----

    @Test
    fun `http url with known extension resolves mime`() {
        assertEquals("image/png", AttachmentSaver.mimeFromUri("https://x.com/a/b/photo.png"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            AttachmentSaver.mimeFromUri("http://x.com/数据表.xlsx")
        )
    }

    @Test
    fun `data url mime is extracted`() {
        assertEquals("image/jpeg", AttachmentSaver.mimeFromUri("data:image/jpeg;base64,xxxx"))
    }

    @Test
    fun `unknown or extensionless url returns null`() {
        assertNull(AttachmentSaver.mimeFromUri("https://x.com/a/b/unknown.zzz"))
        assertNull(AttachmentSaver.mimeFromUri("https://x.com/a/b/noext"))
    }

    // ---- sniffMime：文件头魔数嗅探（无 MIME 线索的附件修正扩展名） ----

    @Test
    fun `magic bytes resolve image formats`() {
        assertEquals("image/png", AttachmentSaver.sniffMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())))
        assertEquals("image/jpeg", AttachmentSaver.sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", AttachmentSaver.sniffMime("GIF89a".toByteArray()))
        assertEquals(
            "image/webp",
            AttachmentSaver.sniffMime("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray())
        )
    }

    @Test
    fun `magic bytes resolve document formats`() {
        assertEquals("application/pdf", AttachmentSaver.sniffMime("%PDF-1.7".toByteArray()))
        assertEquals("application/zip", AttachmentSaver.sniffMime(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
    }

    @Test
    fun `unrecognized head returns null`() {
        assertNull(AttachmentSaver.sniffMime("random bytes!!".toByteArray()))
        assertNull(AttachmentSaver.sniffMime(ByteArray(0)))
    }

    // ---- octet-stream 不硬造扩展名 ----

    @Test
    fun `octet-stream mime keeps name extensionless`() {
        assertEquals("随机风景图片", AttachmentSaver.sanitizeFileName("随机风景图片", "application/octet-stream"))
        assertEquals("report", AttachmentSaver.sanitizeFileName("report", "application/octet-stream"))
    }
}
