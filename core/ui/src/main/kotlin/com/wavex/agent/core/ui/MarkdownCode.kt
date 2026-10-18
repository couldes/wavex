package com.wavex.agent.core.ui

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class CodeColors(
    val keyword: Color,
    val builtin: Color,
    val string: Color,
    val number: Color,
    val comment: Color
)

private fun codeColors(darkBg: Boolean) = if (darkBg) {
    // One Dark 风格（暗底亮色）
    CodeColors(
        keyword = Color(0xFFC678DD),
        builtin = Color(0xFFE5C07B),
        string = Color(0xFF98C379),
        number = Color(0xFFD19A66),
        comment = Color(0xFF8B95A3)
    )
} else {
    // GitHub Light 风格（亮底深色）
    CodeColors(
        keyword = Color(0xFFCF222E),
        builtin = Color(0xFF8250DF),
        string = Color(0xFF0A306D),
        number = Color(0xFF0550AE),
        comment = Color(0xFF6E7781)
    )
}

private enum class CodeToken { Default, Keyword, Builtin, Str, Number, Comment }

private val BASH_LANGS = setOf("bash", "sh", "shell", "zsh", "console", "terminal")
private val PY_LANGS = setOf("python", "python3", "py")
private val PLAIN_LANGS = setOf("text", "txt", "plain", "plaintext", "output", "log")

private val BASH_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
    "case", "esac", "in", "function", "return", "exit", "break", "continue",
    "export", "local", "readonly", "source", "alias", "set", "unset", "shift",
    "trap", "eval", "exec", "wait"
)
private val BASH_BUILTINS = setOf(
    "echo", "printf", "read", "cd", "ls", "cat", "pwd", "mkdir", "rmdir", "rm", "cp", "mv",
    "touch", "ln", "chmod", "chown", "grep", "sed", "awk", "find", "xargs", "tee", "head",
    "tail", "sort", "uniq", "cut", "tr", "wc", "diff", "patch", "file", "strings", "objdump",
    "readelf", "nm", "xxd", "hexdump", "base64", "openssl", "curl", "wget", "tar", "zip",
    "unzip", "gzip", "git", "python", "python3", "pip", "pip3", "node", "npm", "java", "javac",
    "adb", "make", "cmake", "gcc", "g++", "clang", "go", "cargo", "rustc", "sudo", "apt",
    "apt-get", "yum", "brew", "which", "whereis", "man", "nano", "vim", "env", "export", "uname",
    "whoami", "id", "ps", "kill", "df", "du", "mount", "lsblk", "ping", "ssh", "scp", "rsync",
    "netstat", "systeminfo", "powershell", "cmd"
)
private val PY_KEYWORDS = setOf(
    "def", "class", "import", "from", "as", "return", "if", "elif", "else", "for", "while",
    "in", "not", "and", "or", "is", "None", "True", "False", "try", "except", "finally",
    "with", "pass", "raise", "lambda", "yield", "global", "nonlocal", "assert", "del",
    "async", "await", "break", "continue", "match", "case"
)
private val PY_BUILTINS = setOf(
    "print", "open", "input", "int", "str", "float", "bool", "bytes", "list", "dict", "set",
    "tuple", "len", "range", "enumerate", "zip", "map", "filter", "sorted", "reversed", "sum",
    "min", "max", "abs", "round", "type", "isinstance", "getattr", "setattr", "hasattr",
    "self", "cls", "super", "object", "hash", "hex", "bin", "oct", "ord", "chr", "format",
    "vars", "dir", "id", "iter", "next", "any", "all", "repr", "os", "sys", "re", "json",
    "time", "math", "random", "subprocess", "socket", "struct", "hashlib", "base64",
    "threading", "asyncio", "pathlib", "datetime", "collections", "itertools", "functools"
)
private val GENERIC_KEYWORDS = setOf(
    "function", "func", "fn", "def", "return", "if", "else", "elif", "fi", "then", "for",
    "while", "do", "done", "switch", "case", "break", "continue", "class", "struct", "enum",
    "interface", "impl", "trait", "let", "var", "const", "val", "new", "public", "private",
    "protected", "static", "final", "abstract", "override", "open", "import", "package",
    "namespace", "using", "try", "catch", "except", "finally", "throw", "throws", "yield",
    "async", "await", "this", "self", "super", "null", "nil", "none", "true", "false", "void",
    "int", "uint", "float", "double", "char", "bool", "boolean", "string", "long", "short",
    "byte", "end", "and", "or", "not", "in", "is", "typeof", "instanceof", "extends",
    "implements", "delete", "typeof", "operator", "sizeof", "const"
)

private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

/** 高亮结果缓存（流式期间只有生长中的代码块会 miss） */
private val codeCache = LruCache<String, AnnotatedString>(256)

/** 超长代码不着色（每帧重复分词的成本不划算） */
private const val MAX_HIGHLIGHT_CHARS = 20000

/** 单元测试入口：验证分词 span 不越界（colors 固定取暗色盘，与主题无关） */
internal fun highlightCode(content: String, language: String): AnnotatedString =
    highlightCode(content, language, codeColors(darkBg = true))

private fun highlightCode(content: String, language: String, colors: CodeColors): AnnotatedString {
    val lang = language.trim().lowercase()
    if (lang in PLAIN_LANGS || content.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(content)
    // key 直接用语言+原文：此前用 hashCode+length，两个不同代码块哈希碰撞且等长时
    // 会命中彼此缓存——AnnotatedString 携带旧文本，第二块会把第一块的代码显示出来。
    // LruCache 的 key 只持引用，原文入键不额外拷贝字符串
    val key = "$lang\u0001$content"
    codeCache.get(key)?.let { return it }
    val spans = when (lang) {
        in BASH_LANGS -> tokenizeBash(content, colors)
        in PY_LANGS -> tokenizePython(content, colors)
        else -> tokenizeGeneric(content, colors)
    }
    val built = buildAnnotatedString {
        var last = 0
        for ((start, end, token) in spans) {
            if (start > last) append(content.substring(last, start))
            val style = when (token) {
                CodeToken.Keyword -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium)
                CodeToken.Builtin -> SpanStyle(color = colors.builtin)
                CodeToken.Str -> SpanStyle(color = colors.string)
                CodeToken.Number -> SpanStyle(color = colors.number)
                CodeToken.Comment -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
                CodeToken.Default -> null
            }
            if (style != null) withStyle(style) { append(content.substring(start, end)) }
            else append(content.substring(start, end))
            last = end
        }
        if (last < content.length) append(content.substring(last))
    }
    codeCache.put(key, built)
    return built
}

/** bash/sh：行首命令、关键字/常用命令、字符串、$变量、数字、#注释 */
private fun tokenizeBash(content: String, colors: CodeColors): List<Triple<Int, Int, CodeToken>> {
    val spans = mutableListOf<Triple<Int, Int, CodeToken>>()
    var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            c == '#' -> {
                val end = content.indexOf('\n', i).let { if (it == -1) content.length else it }
                spans.add(Triple(i, end, CodeToken.Comment)); i = end
            }
            c == '\'' -> {
                // bash 单引号内无转义：遇到下一个 ' 或行尾即结束
                var j = i + 1
                while (j < content.length && content[j] != '\'' && content[j] != '\n') j++
                val stop = if (j < content.length && content[j] == '\'') j else j - 1
                spans.add(Triple(i, stop + 1, CodeToken.Str))
                i = (stop + 1).coerceAtLeast(i + 1) // 空串时也要前进，避免死循环
            }
            c == '"' -> {
                var j = i + 1
                while (j < content.length && content[j] != '"' && content[j] != '\n') {
                    // 转义只跳过下一个字符；反斜杠是最后一个字符时没有下一个，
                    // 若不判界 j 会越过 length，span 的 end 变成 length + 1，
                    // substring 抛 StringIndexOutOfBoundsException（线上闪退）
                    if (content[j] == '\\' && j + 1 < content.length) j++
                    j++
                }
                val stop = if (j < content.length && content[j] == '"') j else j - 1
                spans.add(Triple(i, stop + 1, CodeToken.Str))
                i = (stop + 1).coerceAtLeast(i + 1) // 空串时也要前进，避免死循环
            }
            c == '$' && i + 1 < content.length && (isIdentStart(content[i + 1]) || content[i + 1] == '{') -> {
                var j = i + 1
                if (content[j] == '{') {
                    val end = content.indexOf('}', j)
                    j = if (end == -1) content.length - 1 else end
                } else while (j + 1 < content.length && isIdentPart(content[j + 1])) j++
                spans.add(Triple(i, j + 1, CodeToken.Number)); i = j + 1
            }
            c.isDigit() -> {
                var j = i
                while (j < content.length && (content[j].isLetterOrDigit() || content[j] == '.')) j++
                spans.add(Triple(i, j, CodeToken.Number)); i = j
            }
            isIdentStart(c) -> {
                var j = i
                while (j < content.length && (isIdentPart(content[j]) || content[j] == '-')) j++
                val word = content.substring(i, j)
                val lineStart = content.lastIndexOf('\n', i).let { if (it == -1) 0 else it + 1 }
                val onlySpacesBefore = content.substring(lineStart, i).isBlank()
                val token = when {
                    word in BASH_KEYWORDS -> CodeToken.Keyword
                    word in BASH_BUILTINS -> CodeToken.Builtin
                    onlySpacesBefore -> CodeToken.Builtin // 行首第一个词 = 命令
                    else -> CodeToken.Default
                }
                if (token != CodeToken.Default) spans.add(Triple(i, j, token))
                i = j
            }
            else -> i++
        }
    }
    return spans
}

/** python：'''/"""三引号串（可跨行）、#注释、@装饰器、关键字/内置名、数字 */
private fun tokenizePython(content: String, colors: CodeColors): List<Triple<Int, Int, CodeToken>> {
    val spans = mutableListOf<Triple<Int, Int, CodeToken>>()
    var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            content.startsWith("\"\"\"", i) || content.startsWith("'''", i) -> {
                val q = content.substring(i, i + 3)
                val end = content.indexOf(q, i + 3)
                val stop = if (end == -1) content.length else end + 3
                spans.add(Triple(i, stop, CodeToken.Str)); i = stop
            }
            c == '#' -> {
                val end = content.indexOf('\n', i).let { if (it == -1) content.length else it }
                spans.add(Triple(i, end, CodeToken.Comment)); i = end
            }
            c == '\'' || c == '"' -> {
                var j = i + 1
                while (j < content.length && content[j] != c && content[j] != '\n') {
                    // 同上：转义跳过前先判界，避免 end 越界
                    if (content[j] == '\\' && j + 1 < content.length) j++
                    j++
                }
                val stop = if (j < content.length && content[j] == c) j else j - 1
                spans.add(Triple(i, stop + 1, CodeToken.Str))
                i = (stop + 1).coerceAtLeast(i + 1) // 空串时也要前进，避免死循环
            }
            c == '@' && i + 1 < content.length && isIdentStart(content[i + 1]) -> {
                var j = i + 1
                while (j < content.length && isIdentPart(content[j])) j++
                spans.add(Triple(i, j, CodeToken.Builtin)); i = j
            }
            c.isDigit() -> {
                var j = i
                while (j < content.length && (content[j].isLetterOrDigit() || content[j] == '.' || content[j] == '_')) j++
                spans.add(Triple(i, j, CodeToken.Number)); i = j
            }
            isIdentStart(c) -> {
                var j = i
                while (j < content.length && isIdentPart(content[j])) j++
                val word = content.substring(i, j)
                val token = when {
                    word in PY_KEYWORDS -> CodeToken.Keyword
                    word in PY_BUILTINS -> CodeToken.Builtin
                    else -> CodeToken.Default
                }
                if (token != CodeToken.Default) spans.add(Triple(i, j, token))
                i = j
            }
            else -> i++
        }
    }
    return spans
}

/** 通用（未标注语言/kotlin/js/go/c…）：//与/* */注释、字符串、@注解、关键字 */
private fun tokenizeGeneric(content: String, colors: CodeColors): List<Triple<Int, Int, CodeToken>> {
    val spans = mutableListOf<Triple<Int, Int, CodeToken>>()
    var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            content.startsWith("//", i) -> {
                val end = content.indexOf('\n', i).let { if (it == -1) content.length else it }
                spans.add(Triple(i, end, CodeToken.Comment)); i = end
            }
            content.startsWith("/*", i) -> {
                val end = content.indexOf("*/", i + 2)
                val stop = if (end == -1) content.length else end + 2
                spans.add(Triple(i, stop, CodeToken.Comment)); i = stop
            }
            c == '\'' || c == '"' || c == '`' -> {
                var j = i + 1
                while (j < content.length && content[j] != c && content[j] != '\n') {
                    // 同上：转义跳过前先判界，避免 end 越界
                    if (content[j] == '\\' && j + 1 < content.length) j++
                    j++
                }
                val stop = if (j < content.length && content[j] == c) j else j - 1
                spans.add(Triple(i, stop + 1, CodeToken.Str))
                i = (stop + 1).coerceAtLeast(i + 1) // 空串时也要前进，避免死循环
            }
            c == '@' && i + 1 < content.length && isIdentStart(content[i + 1]) -> {
                var j = i + 1
                while (j < content.length && isIdentPart(content[j])) j++
                spans.add(Triple(i, j, CodeToken.Builtin)); i = j
            }
            c.isDigit() -> {
                var j = i
                while (j < content.length && (content[j].isLetterOrDigit() || content[j] == '.')) j++
                spans.add(Triple(i, j, CodeToken.Number)); i = j
            }
            isIdentStart(c) -> {
                var j = i
                while (j < content.length && isIdentPart(content[j])) j++
                if (content.substring(i, j) in GENERIC_KEYWORDS) {
                    spans.add(Triple(i, j, CodeToken.Keyword))
                }
                i = j
            }
            else -> i++
        }
    }
    return spans
}

/**
 * 代码块：标题栏（语言标签 + 复制按钮）+ 溢出时横向滚动 + 轻量语法着色。
 * 长行不再截断（横向滚），bash/python 等常用语言关键字/字符串/注释着色，
 * text/plain 保持素色。不溢出时不加 horizontalScroll（不消费横拖，抽屉可用）。
 */
@Composable
internal fun CodeBlock(block: Block.Code, onCopyCode: (String) -> Unit) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    // 暗色代码底（surfaceVariant 亮度 < 0.5）用暗色盘，否则用亮色盘
    val colors = remember(codeBg) { codeColors(codeBg.luminance() < 0.5f) }
    val highlighted = remember(block.content, block.language, colors) {
        highlightCode(block.content, block.language, colors)
    }
    // 溢出检测：用 TextMeasurer 预测量整段（单行约束）宽度，与容器宽（BoxWithConstraints）比较
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 不强制 fillMaxWidth：短代码块跟随气泡宽度；溢出检测用 maxWidth（容器约束）不变
    BoxWithConstraints(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(codeBg)
            .border(0.5.dp, borderColor.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
    ) {
        val availablePx = with(density) { this@BoxWithConstraints.maxWidth.toPx() - 24.dp.toPx() }
        Column {
        // 标题栏：语言标签 + 复制
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                block.language.uppercase().ifBlank { "CODE" },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onCopyCode(block.content) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制代码",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = borderColor.copy(alpha = 0.5f))
        // 只在内容真的溢出时才套 horizontalScroll：不溢出的代码块不消费横拖，
        // 屏幕左缘/代码块上也能划出抽屉（溢出时长行横向滚动优先）
        val scrollState = rememberScrollState()
        val overflow = remember(highlighted, density, availablePx) {
            val textWidthPx = measurer.measure(
                highlighted,
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp
                ),
                softWrap = false,
                maxLines = 1,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = 100000)
            ).size.width
            textWidthPx > availablePx
        }
        if (overflow) {
            Box(Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                Text(
                    highlighted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        } else {
            Text(
                highlighted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        }
    }
}

