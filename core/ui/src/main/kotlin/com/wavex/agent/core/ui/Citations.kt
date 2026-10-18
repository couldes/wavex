package com.wavex.agent.core.ui

/**
 * 引用标记 `[cite:abc123]` 的解析。
 *
 * 模型在联网回答里用这个标记指向某条搜索结果。渲染时换成可点击的角标，
 * 点击跳到对应 URL。
 *
 * 两条刻意保留的边界：
 * - 查不到的 id 原样保留（不静默删掉）：模型引用了一个不存在的源，
 *   用户该看到这一点，而不是以为它没引用；
 * - 标记只在正文出现，工具卡片自己不带标记。
 */
data class CitationMarker(
    /** 正文里的原始区间 [start, end) */
    val start: Int,
    val end: Int,
    val id: String,
)

/** 一条引用解析出的展示目标 */
data class CitationTarget(val number: Int, val url: String)

/** 匹配 `[cite:id]`；id 限定字母数字与 `_-`，避免把 markdown 链接吃进来 */
private val CITE = Regex("""\[cite:([A-Za-z0-9_-]{1,32})]""")

fun findCitations(text: String): List<CitationMarker> =
    CITE.findAll(text).map { m ->
        CitationMarker(start = m.range.first, end = m.range.last + 1, id = m.groupValues[1])
    }.toList()

/**
 * 把 `[cite:id]` 换成可点的 `[n]` 角标（markdown 形态 `[[n](url)]`）。
 *
 * 走 markdown 链接形态而不是自造角标：现有的行内解析器已经把
 * `[标签](网址)` 渲染成可点击链接（主题色 + 下划线，点击经 Compose 的
 * LinkAnnotation.Url 交给系统打开），复用它比给解析器加一条引用分支风险小得多。
 *
 * [resolve] 返回 null 表示这个 id 在本次回答里查不到——此时保留原标记。
 */
fun replaceCitations(text: String, resolve: (String) -> CitationTarget?): String {
    val markers = findCitations(text)
    if (markers.isEmpty()) return text
    // 编号按「首次出现顺序」在整条消息内统一分配：两次搜索各自的第 1 条
    // 都带 index=1，直接用会渲染出两个 [1] 指向不同页面
    val numbers = LinkedHashMap<String, Int>()
    val sb = StringBuilder(text.length)
    var cursor = 0
    for (m in markers) {
        val target = resolve(m.id) ?: continue // 查不到：原样保留
        val n = numbers.getOrPut(m.id) { numbers.size + 1 }
        sb.append(text, cursor, m.start)
        // 外层方括号留在链接外面：markdown 的链接标签本身不能含 `]`，
        // 直接写 `[1](url)` 渲染出来是一个孤零零的「1」（不像引用角标），
        // 写成 `[[1](url)]` 才是「[1]」且中间的 1 可点
        sb.append('[')
        sb.append('[').append(n).append(']')
        sb.append('(').append(encodeForMarkdownLink(target.url)).append(')')
        sb.append(']')
        cursor = m.end
    }
    if (cursor == 0) return text
    sb.append(text, cursor, text.length)
    return sb.toString()
}

/**
 * 把 URL 里会破坏 markdown 链接语法的字符转义掉。
 *
 * 行内解析器的链接正则要求地址里没有 `)` 与空白，Bing RSS 偶尔会回
 * 未编码的括号/空格；不处理的话整条 `[1](url)` 会原样漏成文本。
 */
private fun encodeForMarkdownLink(url: String): String {
    if (url.none { it == ')' || it == '(' || it.isWhitespace() }) return url
    val sb = StringBuilder(url.length + 8)
    for (c in url) {
        when {
            c == ')' || c == '(' -> sb.append('%').append(c.code.toString(16).uppercase())
            c.isWhitespace() -> sb.append("%20")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
