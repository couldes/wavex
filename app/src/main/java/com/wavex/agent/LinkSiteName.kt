package com.wavex.agent

/**
 * 链接标签如果只是裸域名，换成可读的站点名。
 *
 * 联网搜索的回答常带 `([github.com](https://github.com/owner/repo))` 这种引用链接，
 * 裸域名会被读成"这点进去是 GitHub 主站"，而它其实指向某个仓库。这里把标签换成站点名，
 * 跳转地址由调用方保持原样。
 *
 * 站点名只能查表：自动规则会把 `platform.claude.com` 推成 "Platform"（错），
 * 所以表里没有的域名一律原样返回，不猜。
 */
internal fun linkSiteName(label: String, url: String): String {
    val host = urlHost(url) ?: return label
    if (normalizeLinkLabel(label) != host) return label
    var candidate = host
    while (candidate.isNotEmpty()) {
        SITE_NAMES[candidate]?.let { return it }
        candidate = candidate.substringAfter('.', "")
    }
    return label
}

/** 标签归一化：剥掉强调符/引号、协议、www.、末尾斜杠与点，供与 host 比对 */
private fun normalizeLinkLabel(label: String): String =
    label.trim('*', '_', '`', '"', '\'', ' ', '\t', '[', ']')
        .lowercase()
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/', '.')

/** 取网址的主机名（去协议/认证信息/端口/大小写/www.）；非 http(s) 网址返回 null */
private fun urlHost(url: String): String? {
    val noScheme = url.substringAfter("://", url)
    if (noScheme == url && url.contains(':')) return null // mailto: 等其它 scheme
    val authority = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringAfterLast('@').substringBeforeLast(':')
        .trimEnd('.').lowercase().removePrefix("www.")
    return host.takeIf { '.' in it }
}

private val SITE_NAMES = mapOf(
    "github.com" to "GitHub",
    "gitlab.com" to "GitLab",
    "huggingface.co" to "Hugging Face",
    "arxiv.org" to "arXiv",
    "reddit.com" to "Reddit",
    "ycombinator.com" to "Hacker News",
    "x.com" to "X",
    "twitter.com" to "Twitter",
    "t.co" to "X",
    "youtube.com" to "YouTube",
    "youtu.be" to "YouTube",
    "wikipedia.org" to "Wikipedia",
    "medium.com" to "Medium",
    "substack.com" to "Substack",
    "stackoverflow.com" to "Stack Overflow",
    "stackexchange.com" to "Stack Exchange",
    "developer.mozilla.org" to "MDN",
    "npmjs.com" to "npm",
    "pypi.org" to "PyPI",
    "openai.com" to "OpenAI",
    "anthropic.com" to "Anthropic",
    "deepmind.google" to "DeepMind",
    "reuters.com" to "Reuters",
    "bloomberg.com" to "Bloomberg",
    "theguardian.com" to "The Guardian",
    "bbc.com" to "BBC",
    "nytimes.com" to "The New York Times",
    "washingtonpost.com" to "The Washington Post",
    "zhihu.com" to "知乎",
    "juejin.cn" to "掘金",
    "csdn.net" to "CSDN",
    "bilibili.com" to "哔哩哔哩",
    "weibo.com" to "微博",
    "douban.com" to "豆瓣",
    "sspai.com" to "少数派",
    "36kr.com" to "36氪",
    "infoq.cn" to "InfoQ",
    "notion.site" to "Notion",
)
