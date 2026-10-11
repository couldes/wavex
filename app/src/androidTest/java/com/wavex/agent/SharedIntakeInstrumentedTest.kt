package com.wavex.agent

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回归：分享流载荷提取（extractSharedStreamUris）。
 * 背景：单文件分享若以 ArrayList<Uri> 走 EXTRA_STREAM（部分来源 App 的实际形态），
 * typed getParcelableExtra 类型不匹配返回 null → 分享被静默丢弃（无附件无提示）。
 */
@RunWith(AndroidJUnit4::class)
class SharedIntakeInstrumentedTest {

    private val pdf1: Uri = Uri.parse("content://test.authority/docs/1.pdf")
    private val pdf2: Uri = Uri.parse("content://test.authority/docs/2.pdf")

    private fun sendIntent(type: String = "application/pdf"): Intent =
        Intent(Intent.ACTION_SEND).apply { setType(type) }

    @Test
    fun send_singleUriExtra_isExtracted() {
        val intent = sendIntent().putExtra(Intent.EXTRA_STREAM, pdf1)
        assertEquals(listOf(pdf1), extractSharedStreamUris(intent))
    }

    @Test
    fun send_arrayListExtra_isExtracted() {
        // 回归场景：来源 App 单文件也塞 ArrayList<Uri>
        val intent = sendIntent().putExtra(Intent.EXTRA_STREAM, arrayListOf(pdf1))
        assertEquals(listOf(pdf1), extractSharedStreamUris(intent))
    }

    @Test
    fun send_nonUriExtra_isDropped() {
        // 类型不匹配的载荷（如 String）不应抛异常，也不该产生附件
        val intent = sendIntent().putExtra(Intent.EXTRA_STREAM, pdf1.toString())
        assertTrue(extractSharedStreamUris(intent).isEmpty())
    }

    @Test
    fun send_clipDataOnly_isExtracted() {
        val intent = sendIntent().apply { clipData = ClipData.newRawUri("f", pdf1) }
        assertEquals(listOf(pdf1), extractSharedStreamUris(intent))
    }

    @Test
    fun send_contentDataUri_nonTextMime_isExtracted() {
        val intent = sendIntent().setDataAndType(pdf1, "application/pdf")
        assertEquals(listOf(pdf1), extractSharedStreamUris(intent))
    }

    @Test
    fun send_textMimeDataUri_notTreatedAsStream() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            setDataAndType(pdf1, "text/plain")
        }
        assertTrue(extractSharedStreamUris(intent).isEmpty())
    }

    @Test
    fun send_withoutAnything_isEmpty() {
        assertTrue(extractSharedStreamUris(sendIntent()).isEmpty())
    }

    @Test
    fun sendMultiple_arrayList_isExtractedInOrder() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setType("application/pdf")
            putExtra(Intent.EXTRA_STREAM, arrayListOf(pdf1, pdf2))
        }
        assertEquals(listOf(pdf1, pdf2), extractSharedStreamUris(intent))
    }

    @Test
    fun sendMultiple_clipData_isExtracted() {
        val clip = ClipData.newRawUri("f", pdf1)
        clip.addItem(ClipData.Item(pdf2))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setType("application/pdf")
            clipData = clip
        }
        assertEquals(listOf(pdf1, pdf2), extractSharedStreamUris(intent))
    }

    @Test
    fun sendMultiple_singleUriExtra_isExtracted() {
        // 个别来源 SEND_MULTIPLE 也塞单个 Uri
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setType("application/pdf")
            putExtra(Intent.EXTRA_STREAM, pdf1)
        }
        assertEquals(listOf(pdf1), extractSharedStreamUris(intent))
    }
}
