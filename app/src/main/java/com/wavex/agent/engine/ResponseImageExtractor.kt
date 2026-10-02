package com.wavex.agent.engine

/**
 * 响应正文图片/文件提取（纯函数，无 Android 依赖，JUnit 直测）：
 * - 三轮正则依序执行：内联 SVG 块 → markdown 包装 data URL → 裸 data URL；
 *   远程图直链 ![alt](https://…) 不提取：模型只发了链接没发图片本体，正文原样保留，
 *   由 MarkdownText 渲染层按链接展示（模型发的图=图片，发的链接=链接）；
 * - 命中项经 writeFile 落盘（生产实现见 data.GeneratedFileStore，测试传假实现），
 *   成功态正文原位替换为行内代码片文件名（附件行展示缩略图/卡片，正文留名作锚点），
 *   失败态留可读文字占位，附件以 (uri, 显示名) 产出；
 * - 显示名净化/去重逻辑与 data.GeneratedFileStore 的 extFor 扩展名表保持同表
 *   （两处互指，勿单边改动）。
 */
internal object ResponseImageExtractor {

    /** 提取出的一个附件：uri 已定稿（落盘 file:// 或远程 URL 原样） */
    data class ExtractedFile(val uri: String, val displayName: String)

    /** 落盘结果：由调用方注入的 writeFile 返回，决定正文占位形态 */
    sealed interface WriteOutcome {
        data class Saved(val uri: String) : WriteOutcome
        data object TooLarge : WriteOutcome
        data object Failed : WriteOutcome
    }

    class Result(val text: String, val files: List<ExtractedFile>)

    // ---------- 成功态代码片占位 ----------

    /** 成功态占位包裹：正文用行内代码片渲染（等宽小字+浅底，一眼可辨「这是附件名」）；
     *  片内按字面渲染——行首 #/1. 等标记、$ 公式、[链接] 都不会误解析，无需任何转义 */
    private fun codeSpan(name: String): String = "`" + name + "`"

    // 两条正则预编译（项目惯例：正则一律顶层常量，避免每帧重编译）
    private val RE_WRAPPED =
        Regex("""!?\[([^\]]*)\]\(data:([-\w.]+/[-\w.+]+);base64,([A-Za-z0-9+/=]+)\)""")
    private val RE_BARE = Regex("""data:([-\w.]+/[-\w.+]+);base64,([A-Za-z0-9+/=]{8,})""")
    private val RE_FORBIDDEN = Regex("[\\\\/:*?\"<>|`\\p{Cntrl}]")

    /** 内联 SVG 块：模型「画图」的惯用输出（gemini 实测）；(?is) 大小写不敏感 + 跨行，非贪婪配对 */
    private val RE_SVG = Regex("""(?is)<svg\b[^>]*>.*?</svg\s*>""")

    /** ``` 围栏区域：成对行首 ``` 之间；数量为奇（只开未关）时文末全算围栏内。svg 提取跳过围栏内命中 */
    private fun fencedRanges(text: String): List<IntRange> {
        val starts = Regex("(?m)^```").findAll(text).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i + 1 < starts.size) {
            ranges += starts[i] until starts[i + 1]
            i += 2
        }
        if (starts.size % 2 == 1) ranges += starts.last()..text.length
        return ranges
    }

    /** mime → 扩展名：与 data.GeneratedFileStore.extFor 同表；未命中取 subtype（去 +xxx），vnd./多点/空 → bin */
    private fun extFor(mime: String): String {
        TABLE[mime.lowercase()]?.let { return it }
        val sub = mime.substringAfter('/', "").substringBefore('+').lowercase()
        return if (sub.isBlank() || sub.startsWith("vnd.") || sub.contains('.')) "bin" else sub
    }

    /** 扩展名精确表：common image / document / audio；xlsx·docx 单列（vnd. 长名不进启发式） */
    private val TABLE = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp",
        "image/gif" to "gif", "image/svg+xml" to "svg",
        "application/pdf" to "pdf", "text/plain" to "txt", "text/csv" to "csv",
        "application/json" to "json", "audio/mpeg" to "mp3", "audio/wav" to "wav",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/zip" to "zip"
    )

    /** 链接文本/替代文字 → 显示名：剔除路径/控制字符/反引号（反引号会截断代码片占位）、
     *  trim 空白与点、限 40 字；空 → 按类型兜底（图片/文件） */
    private fun sanitize(raw: String): String =
        RE_FORBIDDEN.replace(raw, "").trim().trim(' ', '.').take(40).trim()

    /** 显示名定稿：净化 + 按 MIME 补/正扩展名（已有正确扩展名则原样保留，不重复叠加） */
    private fun displayName(alt: String, mime: String): String {
        val ext = extFor(mime)
        val desired = if (ext.isBlank()) "" else ".$ext"
        // 空 alt 兜底按类型分名：图片 → 图片、其余 → 文件（名字现在在正文可见，得分对）
        val name = sanitize(alt).ifBlank { if (mime.startsWith("image/")) "图片" else "文件" }
        if (desired.isEmpty() || name.endsWith(desired, ignoreCase = true)) return name
        // 尾段形似扩展名（如 .jpg/.final）剥掉再补正确扩展名；中文等非扩展名尾段保留全名（补正而非叠加）
        return stripExtLike(name).ifBlank { "文件" } + desired
    }

    /** 形似扩展名的尾段：末点后 1-5 位字母数字（png/jpg2…）；中文等不算 */
    private val RE_TAIL_EXT = Regex("""^[A-Za-z0-9]{1,5}$""")

    /** 剥掉形似扩展名的尾段；无点/中文尾段原样返回。
     *  「1. 最终版」这类名字中的点属内容，不剥——只认真正的扩展名尾段。 */
    private fun stripExtLike(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val tail = name.substring(dot + 1)
        return if (RE_TAIL_EXT.matches(tail)) name.substring(0, dot) else name
    }

    /** 同消息内重名去重：冲突追加 -2/-3（插在扩展名前），首次出现保持原名 */
    private fun dedupe(name: String, seen: MutableMap<String, Int>): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val n = seen.merge(base, 1, Int::plus)
        return if (n == 1) name else "$base-$n$ext"
    }

    /** 未闭合/闭合通用的 data URL 扫描正则（预编译顶层常量惯例在此以对象内 private val 承载） */
    private val RE_ANY_DATA_URL = Regex("""data:[-\w.]+/[-\w.+]+;base64,[A-Za-z0-9+/=]+""")
    private val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    /**
     * 增量扫描 [scanFrom, prefix.length) 内新出现的 data URL，与已知区域合并：
     * - 闭合判定：base64 串后下一字符存在且非 base64 字符（含收尾括号）；未闭合则区域延伸到 prefix 末尾；
     * - 已知区域重新求范围：开放区域随前缀增长或闭合（其起点在 scanFrom 之前，增量扫不到，须逐个回看）；
     * - 已知区域内起点的新匹配视为同一 URL，跳过；结果按 first 排序去重。
     * 调用方持有返回值与 scanFrom（上一帧前缀长度），每帧成本 O(已知区域数 + 新增尾部)。
     */
    fun updateRegions(prefix: String, known: List<IntRange>, scanFrom: Int): List<IntRange> {
        val regions = mutableListOf<IntRange>()
        // 已知区域续扫：从上次结尾起逐字符消费 base64（区域只会向后增长），
        // 直到非 base64 字符（闭合）或前缀末尾（仍开放）。旧实现每帧对每个开放区域
        // 整段重跑正则——15MB 图片流式期间每帧扫全量 payload，主线程必卡（review 修复）
        for (r in known) {
            var i = r.last + 1
            while (i < prefix.length && prefix[i] in BASE64_CHARS) i++
            regions += if (i >= prefix.length) r.first until prefix.length else r.first until i
        }
        // 新区域：增量尾段中，起点不落在已知区域内的匹配；闭合判定同上
        for (m in RE_ANY_DATA_URL.findAll(prefix, scanFrom.coerceAtLeast(0))) {
            if (regions.any { it.first <= m.range.first && m.range.first <= it.last }) continue
            val next = prefix.getOrNull(m.range.last + 1)
            regions += if (next == null) m.range.first until prefix.length else m.range.first..m.range.last
        }
        return regions.sortedBy { it.first }.distinct()
    }

    /** 区域替换为占位「🖼 图片生成中…」；无区域时原样返回同一引用（零分配，流式热路径） */
    fun applyMask(prefix: String, regions: List<IntRange>): String {
        if (regions.isEmpty()) return prefix
        val sb = StringBuilder(prefix.length)
        var cursor = 0
        for (r in regions.sortedBy { it.first }) {
            if (r.first > cursor) sb.append(prefix, cursor, r.first)
            sb.append("🖼 图片生成中…")
            cursor = maxOf(cursor, r.last + 1)
        }
        if (cursor < prefix.length) sb.append(prefix, cursor, prefix.length)
        return sb.toString()
    }

    /** 结尾未闭合的 data URL（含 markdown 前缀）替换为 [图片：传输中断] 或 [附件：传输中断]（按 MIME 是否 image 开头）；闭合或无 URL 原样返回 */
    fun stripIncomplete(text: String): String {
        val tail = Regex("""(!?\[[^\]]*\]\()?(data:[-\w.]+/[-\w.+]+;base64,[A-Za-z0-9+/=]*)${'$'}""").find(text)
            ?: return text
        val mime = tail.groupValues[2].substringBefore(';').removePrefix("data:")
        val ph = if (mime.startsWith("image/")) "[图片：传输中断]" else "[附件：传输中断]"
        return text.substring(0, tail.range.first) + ph
    }

    /**
     * 结构化图片（delta.images[]）→ 附件：data: 走 writeFile（失败返回 null）；http(s) URL 原样；
     * 非法 scheme 返回 null。显示名必须带图片扩展名——isImageAttachment 对 file:// 只认扩展名，
     * 缺扩展名的结构化图会被 UI 渲染成文件卡片而非缩略图（review 修复）。
     */
    fun structuredToAttachment(
        url: String, index: Int,
        writeFile: (mime: String, base64: String, displayName: String) -> WriteOutcome
    ): ExtractedFile? {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // 远程结构化图必是图片；扩展名从 URL 路径取，取不到按 png 兜底（仅影响显示名）
            val pathExt = url.substringBefore('?').substringAfterLast('/', "")
                .substringAfterLast('.', "")
            val ext = if (pathExt.length in 1..5 && pathExt.all { it.isLetterOrDigit() }) pathExt.lowercase() else "png"
            return ExtractedFile(url, "生成图片-$index.$ext")
        }
        val m = RE_ANY_DATA_URL.matchEntire(url) ?: return null
        val mime = m.value.substringAfter("data:").substringBefore(';')
        val payload = m.value.substringAfter(";base64,")
        val name = "生成图片-$index.${extFor(mime)}"
        val outcome = writeFile(mime, payload, name)
        return (outcome as? WriteOutcome.Saved)?.let { ExtractedFile(it.uri, name) }
    }

    /** 成功态 → 代码片文件名（附件行展示缩略图/卡片，正文留名作锚点）；失败态保留可读说明 */
    // ---------- 结构化图片（delta.images）的正文名 ----------

    /** 结构化图不经过正文（无 data URL 可替换），提取占位覆盖不到：
     *  文件名以代码片行作为正文名，与正文提取占位同形态。 */
    fun structuredNotes(names: List<String>): String = names.joinToString("\n") { codeSpan(it) }

    /** 正文 + 结构化图片名合并：正文空白则仅名字（纯图回复），否则正文后换行追加 */
    fun withNotes(base: String, notes: String): String = when {
        notes.isEmpty() -> base
        base.isBlank() -> notes
        else -> base + "\n" + notes
    }

    private fun placeholder(mime: String, outcome: WriteOutcome, displayName: String): String =
        when (outcome) {
            is WriteOutcome.Saved -> codeSpan(displayName)
            WriteOutcome.Failed ->
                if (mime.startsWith("image/")) "[图片：保存失败]" else "[附件：保存失败]"
            WriteOutcome.TooLarge -> "[附件：过大未保存]"
        }

    /**
     * 扫描正文全部 base64 data URL（任意包装/裸），
     * 产出附件列表并改写正文：成功态原位替换为行内代码片文件名（片内字面渲染，见 codeSpan），
     * 失败态留占位：Failed → [图片：保存失败] / [附件：保存失败]；
     * TooLarge → [附件：过大未保存]（含图片，统一形态）。
     * 远程图直链 ![alt](https://...) 不在扫描范围：模型只发了链接非图片本体，正文原样保留。
     */
    fun process(
        text: String,
        writeFile: (mime: String, base64: String, displayName: String) -> WriteOutcome
    ): Result {
        val files = mutableListOf<ExtractedFile>()
        val seen = HashMap<String, Int>()

        fun take(mime: String, payload: String, alt: String): String {
            val name = dedupe(displayName(alt, mime), seen)
            val outcome = writeFile(mime, payload, name)
            if (outcome is WriteOutcome.Saved) files.add(ExtractedFile(outcome.uri, name))
            return placeholder(mime, outcome, name)
        }

        // 轮 0：内联 SVG 块——模型「画图」的惯用输出，源码是明文（非 base64）：
        // UTF-8 → base64 编码后走 writeFile 落盘为 .svg 附件，正文原位替换为代码片锚点。
        // ``` 围栏内的 svg 是代码展示意图（模型贴代码示例），跳过不提取
        val fenced = fencedRanges(text)
        var out = RE_SVG.replace(text) { m ->
            if (fenced.any { m.range.first in it }) {
                m.value
            } else {
                take(
                    "image/svg+xml",
                    java.util.Base64.getEncoder().encodeToString(m.value.toByteArray(Charsets.UTF_8)),
                    ""
                )
            }
        }
        // 轮 1：markdown 包装（图/链接）——整体替换，payload 不再进轮 2
        out = RE_WRAPPED.replace(out) { m ->
            take(m.groupValues[2], m.groupValues[3], m.groupValues[1])
        }
        // 轮 2：裸 data URL——仅替换 URL 本身（无 alt，显示名走兜底）
        out = RE_BARE.replace(out) { m ->
            take(m.groupValues[1], m.groupValues[2], "")
        }
        // 远程图直链 ![alt](https://…) 与普通链接一律不动（模型只发了链接，非图片本体），
        // 正文原样返回；MarkdownText 渲染层把 ![alt](url) 按链接展示
        return Result(out, files)
    }
}

