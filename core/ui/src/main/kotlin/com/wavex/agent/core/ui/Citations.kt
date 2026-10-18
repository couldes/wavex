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
 * 把 `[cite:id]` 换成 markdown 链接 `[n](url)`。
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
    val sb = StringBuilder(text.length)
    var cursor = 0
    for (m in markers) {
        val target = resolve(m.id) ?: continue // 查不到：原样保留
        sb.append(text, cursor, m.start)
        sb.append('[').append(target.number).append(']')
        sb.append('(').append(target.url).append(')')
        cursor = m.end
    }
    if (cursor == 0) return text
    sb.append(text, cursor, text.length)
    return sb.toString()
}
