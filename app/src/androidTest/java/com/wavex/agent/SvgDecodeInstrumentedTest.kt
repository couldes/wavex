package com.wavex.agent

import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG 渲染链路诊断（用户实测：提取成功但缩略图不显示）。
 * 复刻 ui.chat.AttachmentThumbnail 的真实加载路径：App 级 ImageLoader（WavexApplication
 * 注册 SvgDecoder）+ String file:// URI + size(256)。失败时错误原样抛出。
 */
class SvgDecodeInstrumentedTest {

    /** 用户实测的 gemini svg（原样，含中文与 emoji） */
    private val modelSvg = """<svg width="300" height="120" xmlns="http://www.w3.org/2000/svg">
  <rect width="100%" height="100%" rx="10" fill="#2563EB"/>
  <text x="50%" y="45%" fill="white" font-size="18" font-family="sans-serif" text-anchor="middle" font-weight="bold">📄 示例附件文件</text>
  <text x="50%" y="70%" fill="#E0E7FF" font-size="12" font-family="sans-serif" text-anchor="middle">Click or save as file</text>
</svg>"""

    private fun writeProbe(): Pair<android.content.Context, java.io.File> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "probe.svg")
        file.writeText(modelSvg)
        return context to file
    }

    @Test
    fun appImageLoaderRendersFileUriStringSvg() = runBlocking<Unit> {
        val (context, file) = writeProbe()
        val request = ImageRequest.Builder(context)
            .data("file://" + file.absolutePath)   // AttachmentThumbnail 同款：String URI
            .size(256)
            .build()
        when (val result = context.imageLoader.execute(request)) {
            is ErrorResult -> throw AssertionError("Coil 加载失败", result.throwable)
            is SuccessResult -> assertNotNull("decode 成功但无 drawable", result.drawable)
        }
    }

    @Test
    fun appImageLoaderRendersFileObjectSvg() = runBlocking<Unit> {
        val (context, file) = writeProbe()
        val request = ImageRequest.Builder(context)
            .data(file)                            // 对照组：直接传 File
            .size(256)
            .build()
        when (val result = context.imageLoader.execute(request)) {
            is ErrorResult -> throw AssertionError("Coil 加载失败", result.throwable)
            is SuccessResult -> assertNotNull("decode 成功但无 drawable", result.drawable)
        }
    }

    /** 落盘往返校验：GeneratedFileStore 的 base64 编解码不破坏内容 */
    @Test
    fun generatedFileStoreSvgRoundtrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.io.File(context.cacheDir, "gen-probe")
        val b64 = java.util.Base64.getEncoder().encodeToString(modelSvg.toByteArray(Charsets.UTF_8))
        val outcome = com.wavex.agent.data.GeneratedFileStore.write(dir, "image/svg+xml", b64)
        assertTrue("落盘失败：$outcome", outcome is com.wavex.agent.engine.ResponseImageExtractor.WriteOutcome.Saved)
        val uri = (outcome as com.wavex.agent.engine.ResponseImageExtractor.WriteOutcome.Saved).uri
        val path = uri.removePrefix("file://")
        assertEquals(modelSvg, java.io.File(path).readText())
    }
}
