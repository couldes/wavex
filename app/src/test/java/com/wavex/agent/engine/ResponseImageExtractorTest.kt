package com.wavex.agent.engine

import com.wavex.agent.engine.ResponseImageExtractor.WriteOutcome.Failed
import com.wavex.agent.engine.ResponseImageExtractor.WriteOutcome.Saved
import com.wavex.agent.engine.ResponseImageExtractor.WriteOutcome.TooLarge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为钉子：正文 data URL 提取与附件产出。
 * 覆盖三种包装形态（markdown 图 / markdown 链接 / 裸 URL）、显示名净化与去重、
 * 成功态原位替换为行内代码片文件名、保存失败 / 超限占位、普通链接与普通文本不受影响。
 * 远程图直链 ![alt](https://…) 不是模型发的图（只是链接），不提取——渲染层按链接展示。
 */
class ResponseImageExtractorTest {

    private val b64 = "aGVsbG8="

    /** 所有写入都成功返回同一 mock uri */
    private val saved: (String, String, String) -> ResponseImageExtractor.WriteOutcome =
        { _, _, _ -> Saved("file://mock") }

    @Test
    fun `markdown data url image named in text`() {
        val r = ResponseImageExtractor.process("看图\n![alt](data:image/png;base64,$b64)", saved)
        // 成功态占位替换为行内代码片文件名（附件行展示本体，正文留名作锚点）
        assertEquals("看图\n`alt.png`", r.text)
        assertEquals(1, r.files.size)
        assertEquals("file://mock", r.files[0].uri)
    }

    @Test
    fun `markdown link form uses alt text as attachment name`() {
        val names = mutableListOf<String>()
        val r = ResponseImageExtractor.process(
            "[报告.pdf](data:application/pdf;base64,$b64)"
        ) { _, _, name -> names.add(name); Saved("file://mock") }
        assertEquals("`报告.pdf`", r.text)
        assertEquals(listOf("报告.pdf"), names)
        assertEquals(1, r.files.size)
    }

    @Test
    fun `bare data url named in line`() {
        val r = ResponseImageExtractor.process("前文 data:image/jpeg;base64,$b64 后文", saved)
        // 行中原位替换；无 alt 的图片兕底名「图片」+ MIME 扩展名
        assertEquals("前文 `图片.jpg` 后文", r.text)
        assertEquals(1, r.files.size)
    }

    @Test
    fun `remote markdown image stays a plain link`() {
        val src = "![图表](https://x/y.png)"
        val r = ResponseImageExtractor.process(src, saved)
        // 图片直链是链接不是模型发的图：正文原样保留（渲染层按链接展示），不出附件
        assertEquals(src, r.text)
        assertEquals(0, r.files.size)
    }

    @Test
    fun `remote image link beside data url only extracts the latter`() {
        // 混排：直链保持链接形态，模型实发的 data 图照常提取（用户规则：发的图=图片，发的链接=链接）
        val r = ResponseImageExtractor.process(
            "![外链](https://x/a.png) 看图 ![图](data:image/png;base64,$b64)", saved
        )
        assertEquals("![外链](https://x/a.png) 看图 `图.png`", r.text)
        assertEquals(1, r.files.size)
        assertEquals("图.png", r.files[0].displayName)
    }

    @Test
    fun `plain links and text untouched`() {
        val src = "说明见 [文档](https://example.com/a) 与 `code`，无图。"
        val r = ResponseImageExtractor.process(src, saved)
        assertEquals(src, r.text)
        assertEquals(0, r.files.size)
    }

    @Test
    fun `display name sanitized and extension corrected`() {
        val names = mutableListOf<String>()
        fun one(alt: String, mime: String): ResponseImageExtractor.Result {
            names.clear()
            return ResponseImageExtractor.process("![$alt](data:$mime;base64,$b64)") { _, _, n ->
                names.add(n); Saved("file://mock")
            }
        }
        one("a/b:c*.png", "image/png")   // 非法字符剔除，扩展名本就正确
        assertEquals("abc.png", names[0])
        one("图表", "image/png")          // 无扩展名按 MIME 补
        assertEquals("图表.png", names[0])
        one("字".repeat(50), "image/png") // 超 40 字截断
        assertEquals("字".repeat(40) + ".png", names[0])
        one("  ", "image/png")           // 空 alt 兜底：图片类→图片，非图片类→文件
        assertEquals("图片.png", names[0])
        one("1. 最终版", "image/png")     // 中文尾段非扩展名：保留全名再补（原实现会截成 "1.png"）
        assertEquals("1. 最终版.png", names[0])
        one("photo.jpg", "image/png")    // 错误扩展名剥掉补正（mime 为 png，.jpg 也算错）
        assertEquals("photo.png", names[0])
        one("photo.bmp", "image/png")    // 错误扩展名剥掉补正
        assertEquals("photo.png", names[0])
        one("报告.final", "image/png")   // 1-5 位字母数字尾段视作扩展名剥换
        assertEquals("报告.png", names[0])
        one("photo.capture", "image/png")// 6 位以上尾段非扩展名：保留全名
        assertEquals("photo.capture.png", names[0])
    }

    @Test
    fun `duplicate names deduped with suffix`() {
        val names = mutableListOf<String>()
        val src = "![图](data:image/png;base64,$b64) 中 ![图](data:image/png;base64,$b64)"
        val r = ResponseImageExtractor.process(src) { _, _, n -> names.add(n); Saved("file://mock") }
        assertEquals(listOf("图.png", "图-2.png"), names)
        assertEquals("`图.png` 中 `图-2.png`", r.text)
        assertEquals(2, r.files.size)
    }

    // ---------- 成功态代码片占位的排版保持与字面渲染防御 ----------

    /** 纯图正文：替换后只剩代码片文件名，附件照常产出 */
    @Test
    fun `image-only text becomes filename`() {
        val r = ResponseImageExtractor.process("![a](data:image/png;base64,$b64)", saved)
        assertEquals("`a.png`", r.text)
        assertEquals(1, r.files.size)
    }

    /** 独占一行夹在文字间：原位替换，排版不动 */
    @Test
    fun `image own line between text lines`() {
        val r = ResponseImageExtractor.process("前文\n![a](data:image/png;base64,$b64)\n后文", saved)
        assertEquals("前文\n`a.png`\n后文", r.text)
    }

    /** 空行分隔的段落间：原样保留空行 */
    @Test
    fun `image between paragraphs keeps original spacing`() {
        val r = ResponseImageExtractor.process("前文\n\n![a](data:image/png;base64,$b64)\n\n后文", saved)
        assertEquals("前文\n\n`a.png`\n\n后文", r.text)
    }

    /** 图在开头/结尾：原位替换，空白原样 */
    @Test
    fun `image at edges kept in place`() {
        assertEquals("`a.png`\n后文", ResponseImageExtractor.process("![a](data:image/png;base64,$b64)\n后文", saved).text)
        assertEquals("前文\n`a.png`", ResponseImageExtractor.process("前文\n![a](data:image/png;base64,$b64)", saved).text)
    }

    /** 代码片内按字面渲染：行首 #/-/1. 不再需要转义，名字里的反引号已由 sanitize 剥除 */
    @Test
    fun `code span placeholder renders markers literally`() {
        fun one(alt: String): ResponseImageExtractor.Result =
            ResponseImageExtractor.process("![$alt](data:image/png;base64,$b64)", saved)
        assertEquals("`#1.png`", one("#1").text)
        assertEquals("#1.png", one("#1").files[0].displayName)
        // ">"/"<" 属非法字符，sanitize 阶段已剔除；反引号同样新增剔除，不会截断代码片
        assertEquals("引用.png", one("> 引用").files[0].displayName)
        assertEquals("`- 备注.png`", one("- 备注").text)
        assertEquals("`1. 结果.png`", one("1. 结果").text)
        assertEquals("`1.2.png`", one("1.2.3").text)
        assertEquals("`2) 图.png`", one("2) 图").text)
        assertEquals("`code.png`", one("co`de").text)
        assertEquals("code.png", one("co`de").files[0].displayName)
        // 行首无害字符不转义
        assertEquals("`图表.png`", one("图表").text)
    }

    /** 非图片空 alt 兑底名维持「文件」 */
    @Test
    fun `blank alt non-image keeps file fallback`() {
        val r = ResponseImageExtractor.process("前文 data:application/pdf;base64,$b64 后文", saved)
        assertEquals("前文 `文件.pdf` 后文", r.text)
    }

    /** 同屏混合：成功态代码片不影响失败态占位文字 */
    @Test
    fun `saved rename keeps failure placeholder intact`() {
        var first = true
        val r = ResponseImageExtractor.process(
            "![ok](data:image/png;base64,$b64) 文字 ![bad](data:image/png;base64,$b64)"
        ) { _, _, _ ->
            if (first) { first = false; Saved("file://mock") } else Failed
        }
        assertEquals("`ok.png` 文字 [图片：保存失败]", r.text)
        assertEquals(1, r.files.size)
    }

    @Test
    fun `failed write yields error placeholder and no file`() {
        val rImg = ResponseImageExtractor.process("![a](data:image/png;base64,$b64)") { _, _, _ -> Failed }
        assertEquals("[图片：保存失败]", rImg.text)
        assertEquals(0, rImg.files.size)

        val rPdf = ResponseImageExtractor.process("[报告](data:application/pdf;base64,$b64)") { _, _, _ -> Failed }
        assertEquals("[附件：保存失败]", rPdf.text)
        assertEquals(0, rPdf.files.size)
    }

    @Test
    fun `too large yields oversize placeholder and no file`() {
        val r = ResponseImageExtractor.process("![a](data:image/png;base64,$b64)") { _, _, _ -> TooLarge }
        assertEquals("[附件：过大未保存]", r.text)
        assertEquals(0, r.files.size)
    }

    // ---------- 流式掩码与中断清理（Task 2） ----------

    /** 未闭合 data URL 区域：从 data: 起点到展示前缀末尾 */
    private val openRegionOf = { prefix: String ->
        ResponseImageExtractor.updateRegions(prefix, emptyList(), 0)
    }

    @Test
    fun `updateRegions finds open url growing with prefix`() {
        val p1 = "前![a](data:image/png;base64,AAA"
        val r1 = openRegionOf(p1)
        assertEquals(listOf(p1.indexOf("data:") until p1.length), r1)
        // 前缀增长：同区域扩大，first 不变
        val p2 = "${p1}BBB"
        val r2 = ResponseImageExtractor.updateRegions(p2, r1, p1.length)
        assertEquals(listOf(p2.indexOf("data:") until p2.length), r2)
    }

    @Test
    fun `updateRegions closes region when terminator arrives`() {
        val closed = "![a](data:image/png;base64,AAAA)"
        val r = ResponseImageExtractor.updateRegions(closed, emptyList(), 0)
        // 闭合区域 = data URL 本体（不含收尾括号）
        assertEquals(listOf(closed.indexOf("data:") until closed.indexOf(')')), r)
    }

    @Test
    fun `updateRegions returns empty when no url`() {
        assertEquals(emptyList<IntRange>(), openRegionOf("普通文本无图"))
    }

    @Test
    fun `applyMask empty regions returns same instance`() {
        val prefix = "纯文本"
        assertSame(prefix, ResponseImageExtractor.applyMask(prefix, emptyList()))
    }

    @Test
    fun `applyMask splices placeholder in region`() {
        val prefix = "看图 data:image/png;base64,AAA 还没完"
        val regions = ResponseImageExtractor.updateRegions(prefix, emptyList(), 0)
        val masked = ResponseImageExtractor.applyMask(prefix, regions)
        assertEquals("看图 🖼 图片生成中… 还没完", masked)
    }

    @Test
    fun `stripIncomplete replaces unterminated tail`() {
        assertEquals(
            "[图片：传输中断]",
            ResponseImageExtractor.stripIncomplete("![a](data:image/png;base64,AAAA")
        )
        assertEquals(
            "前文 [图片：传输中断]",
            ResponseImageExtractor.stripIncomplete("前文 data:image/png;base64,AAAA")
        )
        assertEquals(
            "[附件：传输中断]",
            ResponseImageExtractor.stripIncomplete("[x](data:application/pdf;base64,AAAA")
        )
    }

    @Test
    fun `stripIncomplete keeps normal text`() {
        val normal = "![完](data:image/png;base64,AAAA) 后续文字"
        assertEquals(normal, ResponseImageExtractor.stripIncomplete(normal))
        assertEquals("普通文本", ResponseImageExtractor.stripIncomplete("普通文本"))
    }

    // ---------- 结构化图片（delta.images[]）→ 附件（Task 5 Step 1） ----------

    @Test
    fun `structured data url converts to attachment`() {
        val r = ResponseImageExtractor.structuredToAttachment("data:image/png;base64,$b64", 1, saved)
        assertEquals("file://mock", r?.uri)
        // 显示名必须带图片扩展名：isImageAttachment 对 file:// 只认扩展名，无扩展名会被渲染成文件卡片
        assertEquals("生成图片-1.png", r?.displayName)
    }

    @Test
    fun `structured remote url passes through`() {
        val r = ResponseImageExtractor.structuredToAttachment("https://x/y.png", 2, saved)
        assertEquals("https://x/y.png", r?.uri)
        assertEquals("生成图片-2.png", r?.displayName)
    }

    @Test
    fun `structured remote url without extension falls back to png`() {
        val r = ResponseImageExtractor.structuredToAttachment("https://x/img", 3, saved)
        assertEquals("https://x/img", r?.uri)
        assertEquals("生成图片-3.png", r?.displayName)
    }

    @Test
    fun `structured invalid scheme returns null`() {
        assertEquals(null, ResponseImageExtractor.structuredToAttachment("ftp://weird", 1, saved))
        assertEquals(null, ResponseImageExtractor.structuredToAttachment("", 1, saved))
    }

    // ---------- 结构化图片（delta.images）的正文名 ----------

    /** 结构化图不经过正文，提取占位覆盖不到：文件名以代码片行作为正文名 */
    @Test
    fun `structured notes one code span per line`() {
        assertEquals(
            "`生成图片-1.png`\n`生成图片-2.jpg`",
            ResponseImageExtractor.structuredNotes(listOf("生成图片-1.png", "生成图片-2.jpg"))
        )
        assertEquals("", ResponseImageExtractor.structuredNotes(emptyList()))
    }

    @Test
    fun `withNotes appends to non-blank base on new line`() {
        assertEquals("看图：\n`a.png`", ResponseImageExtractor.withNotes("看图：", "`a.png`"))
    }

    @Test
    fun `withNotes replaces blank base with notes`() {
        assertEquals("`a.png`", ResponseImageExtractor.withNotes("", "`a.png`"))
        assertEquals("`a.png`", ResponseImageExtractor.withNotes("  ", "`a.png`"))
    }

    @Test
    fun `withNotes base untouched when no notes`() {
        assertEquals("正文", ResponseImageExtractor.withNotes("正文", ""))
    }

    @Test
    fun `structured failed write returns null`() {
        assertEquals(null, ResponseImageExtractor.structuredToAttachment("data:image/png;base64,$b64", 1) { _, _, _ -> Failed })
    }

    // ---------- 轮 0：内联 SVG 块（模型「画图」惯用输出，gemini 实测） ----------

    @Test
    fun `inline svg extracted as svg attachment with source roundtrip`() {
        val svg = """<svg width="300" height="120" xmlns="http://www.w3.org/2000/svg"><rect fill="#2563EB"/><text>示例</text></svg>"""
        var written: Pair<String, String>? = null
        val r = ResponseImageExtractor.process("前文\n$svg\n后文") { mime, b64, _ ->
            written = mime to b64; Saved("file://mock")
        }
        assertEquals(1, r.files.size)
        assertTrue("显示名应带 .svg：${r.files[0].displayName}", r.files[0].displayName.endsWith(".svg"))
        // 正文：svg 源码移除，留代码片锚点
        assertEquals(false, r.text.contains("<svg"))
        assertTrue(r.text.contains("`"))
        // base64 往返：落盘字节还原后与原源码逐字节一致
        assertEquals("image/svg+xml", written?.first)
        assertEquals(svg, String(java.util.Base64.getDecoder().decode(written?.second), Charsets.UTF_8))
    }

    @Test
    fun `svg inside code fence stays as code`() {
        val t = """
            |```xml
            |<svg width="1" xmlns="x"></svg>
            |```
            |""".trimMargin()
        val r = ResponseImageExtractor.process(t, saved)
        assertEquals(0, r.files.size)
        assertTrue(r.text.contains("<svg"))
    }

    @Test
    fun `svg after unclosed fence stays as code`() {
        // 只开了围栏没关：到文末都算围栏内，不提取
        val t = "```xml\n<svg xmlns=\"x\"></svg>\n"
        val r = ResponseImageExtractor.process(t, saved)
        assertEquals(0, r.files.size)
    }

    @Test
    fun `multiple inline svgs both extracted with dedupe`() {
        val svg1 = """<svg xmlns="x"><rect/></svg>"""
        val r = ResponseImageExtractor.process("$svg1 和 $svg1", saved)
        assertEquals(2, r.files.size)
        assertTrue(r.files[0].displayName.endsWith(".svg"))
        assertTrue("重名应去重：${r.files[1].displayName}", r.files[1].displayName != r.files[0].displayName)
    }

}