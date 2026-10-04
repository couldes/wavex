package com.wavex.agent.network

import com.wavex.agent.data.Provider
import com.wavex.agent.model.ApiProtocol
import com.wavex.agent.model.ChatRequestMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 请求执行与兼容缓存按会话隔离；生产默认会话仍由 ApiClient 持有。 */
internal class HttpApiSession(
    private val client: OkHttpClient,
    private val readTimeouts: ApiReadTimeouts = ApiReadTimeouts(),
    private val recordUsage: (String, Provider, StreamUsage?, Int, Long, String) -> Unit = UsageTracker::record
) : ModelService {
    /**
     * 网关实际接受的鉴权方言，按「服务商+地址」记在进程内。
     * 落盘没必要：协议判断本身是地址字符串的纯函数，重算是纳秒级；这里记的只是
     * 探测产生的一次额外 401 往返，重启后最多再付一次。
     */
    private val dialectByProvider = ConcurrentHashMap<String, AuthDialect>()

    /** 记住不支持 stream_options 的 Provider：请求失败且错误体含该字样时永久摘除注入（Task 5 降级保险） */
    private val streamOptionsUnsupported: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun dialectKey(provider: Provider) = "${provider.id}|${provider.baseUrl.trim()}"

    private fun dialectFor(provider: Provider, protocol: ApiProtocol): AuthDialect =
        dialectByProvider[dialectKey(provider)] ?: Connection.defaultDialect(protocol)

    /**
     * 非流式请求的统一出口：首选方言拿到 401/403 时换另一种再试一次，
     * 只要不再是鉴权拒绝就记住生效的方言。返回的 Response 由调用方关闭。
     */
    private fun executeWithAuthFallback(
        provider: Provider,
        url: String,
        body: String?,
        // 剥高兼容子路径后的根域名候选走 OpenAI 方言；不传则按 baseUrl 推断。
        //（此前参数被同名局部变量遮蔽，listModels 传入的 candidate.protocol 被忽略——
        // Anthropic 网关的根域名 /models 候选先用 x-api-key 白付一次 401 才回退 Bearer）
        protocol: ApiProtocol = detectProtocol(provider.baseUrl)
    ): Pair<okhttp3.Response, AuthDialect> {
        val first = dialectFor(provider, protocol)
        val attempt = send(provider, url, body, protocol, first)
        if (attempt.isSuccessful || !isAuthRejected(attempt.code)) return attempt to first
        attempt.close()

        val alt = Connection.otherDialect(first)
        val retry = send(provider, url, body, protocol, alt)
        if (retry.isSuccessful || !isAuthRejected(retry.code)) {
            dialectByProvider[dialectKey(provider)] = alt
        }
        return retry to alt
    }

    private fun isAuthRejected(code: Int) = code == 401 || code == 403

    private fun send(
        provider: Provider,
        url: String,
        body: String?,
        protocol: ApiProtocol,
        dialect: AuthDialect
    ): okhttp3.Response {
        val builder = Connection.applyAuth(Request.Builder().url(url), provider.apiKey, protocol, dialect)
        val request = if (body == null) builder.build()
        else builder.post(body.toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute()
    }

    /**
     * 自动命名（ChatGPT/Gemini 式）：用当前模型给对话生成简短标题。
     * transcript 由调用方按 TitlePolicy 构建（首题=首轮问答，演化=最近几轮），
     * 非流式请求；任何失败都返回 null（调用方首题回退首条消息截断，演化保留旧题）。
     * Anthropic 协议服务商用 /v1/messages（非流式）实现同等效果。
     */
    override suspend fun generateTitle(provider: Provider, transcript: String): String? = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank() || provider.apiKey.isBlank()) return@withContext null
        if (transcript.isBlank()) return@withContext null
        try {
            val titlePrompt =
                "为下面的对话生成一个简短标题。要求：直接输出标题本身；不超过14个字；不加引号、不加任何前后缀；概括用户的主要意图。"
            val protocol = detectProtocol(provider.baseUrl)
            val payload = if (protocol == ApiProtocol.ANTHROPIC) {
                JSONObject()
                    .put("model", provider.model)
                    .put("max_tokens", 50)
                    .put("system", titlePrompt)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", transcript))
                    )
                    .toString()
            } else {
                JSONObject()
                    .put("model", provider.model)
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", titlePrompt))
                            .put(JSONObject().put("role", "user").put("content", transcript))
                    )
                    .put("stream", false)
                    .toString()
            }
            val t0 = System.nanoTime()
            val (response, _) = executeWithAuthFallback(
                provider, Connection.chatEndpoint(provider.baseUrl, protocol), payload
            )
            response.use {
                if (!it.isSuccessful) {
                    recordUsage("title", provider, null, it.code, elapsedMs(t0),
                        formatApiError(it.code, it.body?.string()?.take(300) ?: ""))
                    return@withContext null
                }
                val body = it.body?.string() ?: run {
                    recordUsage("title", provider, null, it.code, elapsedMs(t0), "空响应")
                    return@withContext null
                }
                val json = JSONObject(body)
                // title 也真实计费：usage 直接在响应体里（非流式两种协议字段名不同）
                recordUsage("title", provider,
                    ApiClient.parseNonStreamingUsage(json, protocol), it.code, elapsedMs(t0), "")
                val text = if (protocol == ApiProtocol.ANTHROPIC) {
                    // Anthropic 非流式响应：content 是内容块数组，取第一个 text 块
                    json.optJSONArray("content")
                        ?.let { arr -> (0 until arr.length()).map { arr.optJSONObject(it) } }
                        ?.firstOrNull { it?.optString("type", "") == "text" }
                        ?.optString("text", "")
                } else {
                    json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                } ?: return@withContext null
                val cleaned = text.trim()
                    .trim('「', '」', '『', '』', '"', '\u201c', '\u201d', '\'', '`', '#', '*', ' ')
                    .replace("\n", " ")
                    .trim()
                    .take(20)
                if (cleaned.isBlank()) null else cleaned
            }
        } catch (e: Exception) {
            // 网络层失败没有响应码：记 0（spec：流级/无响应 = 0）
            recordUsage("title", provider, null, 0, 0, e.message ?: "")
            null
        }
    }

    /** /models 的结果：ids=null 表示没拿到列表（网关不开放 / 鉴权被拒 / 网络异常） */
    internal data class ModelsOutcome(
        val ids: List<String>?,
        val dialect: AuthDialect?,
        val httpCode: Int,
        val latencyMs: Long
    )

    /**
     * 拉模型列表。鉴权方言由 executeWithAuthFallback 决定，命中后记在进程内，
     * 后续对话请求直接走生效的那种，不再多付一次 401。
     * 按候选端点依次尝试：Anthropic 兼容层普遍不挂 /models（404/405），
     * 此时剥掉兼容子路径后在根域名上试 OpenAI 格式端点（同 key 可用，cc-switch 同款）。
     */
    private suspend fun listModels(provider: Provider): ModelsOutcome {
        val t0 = System.nanoTime()
        for (candidate in Connection.modelsCandidates(provider.baseUrl, detectProtocol(provider.baseUrl))) {
            try {
                val (response, dialect) = executeWithAuthFallback(
                    provider, candidate.url, null, candidate.protocol
                )
                response.use {
                    when {
                        // 该端点不提供 /models：换下一个候选
                        it.code == 404 || it.code == 405 -> null
                        !it.isSuccessful -> ModelsOutcome(null, dialect, it.code, elapsedMs(t0))
                        else -> ModelsOutcome(parseModelIds(it), dialect, it.code, elapsedMs(t0))
                    }
                }?.let { return it }
            } catch (_: Exception) {
                // 网络层失败对同主机的所有候选一样：直接放弃（同 cc-switch）
                return ModelsOutcome(null, null, 0, elapsedMs(t0))
            }
        }
        return ModelsOutcome(null, null, 0, elapsedMs(t0))
    }

    /** /models 响应体 → 模型 id 列表（OpenAI 与 Anthropic 的 /models 都是 data[].id 形状）。 */
    private fun parseModelIds(response: okhttp3.Response): List<String> {
        val ids = mutableListOf<String>()
        JSONObject(response.body?.string() ?: "{}").optJSONArray("data")?.let { data ->
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id")
                if (id.isNotBlank()) ids.add(id)
            }
        }
        return ids.sorted()
    }

    private fun elapsedMs(nanos: Long): Long = (System.nanoTime() - nanos) / 1_000_000

    /** 拉取模型列表；所有候选都拿不到时回退到预设名单。Anthropic 协议先试 /anthropic/v1/models，
     *  404 后自动换根域名上的 OpenAI 格式端点（见 Connection.modelsCandidates）。 */
    override suspend fun fetchModels(provider: Provider): List<String>? = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank()) return@withContext null
        listModels(provider).ids?.takeIf { it.isNotEmpty() }
            ?: ApiClient.anthropicFallbackModels(provider, detectProtocol(provider.baseUrl) == ApiProtocol.ANTHROPIC)
    }

    /**
     * 流式对话。每收到一段增量文本就回调 onDelta(content 增量, reasoning 增量)。
     * 协议按 Base URL 自动选择（OpenAI 兼容 / Anthropic），调用方不感知差异。
     * 思考型模型（qwen3/deepseek/gemini/claude）会先流式输出思考再输出正文；
     * 两个增量分开上报，调用方自行决定如何展示思考过程。
     * 协程取消时立即抠断网络请求（invokeOnCompletion 关闭 socket，阻塞中的读取立即中断），
     * 已收到的部分由调用方保留。
     */
    override suspend fun streamChat(
        provider: Provider,
        history: List<ChatRequestMessage>,
        reasoningEffort: String?,
        webSearch: Boolean,
        onDelta: (content: String, reasoning: String) -> Unit,
        onImage: (String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val protocol = detectProtocol(provider.baseUrl)
        val url = Connection.chatEndpoint(provider.baseUrl, protocol)

        // 瞬时错误（429/5xx）自动重试：中转站上游抖动很常见（实测 503 会突然出现），
        // 只对响应阶段的状态码重试（此时尚未输出任何流式内容，无重复风险）；
        // 一旦开始吐流就不再重试。退避 0.8s/2s，429 多等一会。
        var dialect = dialectFor(provider, protocol)
        var authTried = false
        var attempt = 0
        // stream_options 降级只限一次：异常网关反复报错时不无限重试（plan Task 5）
        var downgraded = false
        // 是否已有任何增量送达调用方：决定连接层异常时保留部分内容还是向上报错
        var receivedAny = false
        val trackedOnDelta: (String, String) -> Unit = { c, r ->
            if (c.isNotEmpty() || r.isNotEmpty()) receivedAny = true
            onDelta(c, r)
        }
        // onImage 同样计入「已有可识别输出」：纯图片流（无正文）不能被误判为空流
        val trackedOnImage: (String) -> Unit = { url ->
            if (url.isNotEmpty()) receivedAny = true
            onImage(url)
        }
        // 工具调用函数名收集：只记名字（协议元数据，非用户内容），去重上限 3 个
        val toolCallNames = mutableListOf<String>()
        val trackedOnToolCall: (String) -> Unit = { name ->
            if (name.isNotBlank() && name != "null" && name !in toolCallNames && toolCallNames.size < 3) {
                toolCallNames.add(name)
            }
        }
        // 埋点：一次逻辑请求一条记录（重试中的 429/5xx 不记，终态才记——plan Task 7）
        val usage = StreamUsage()
        val t0 = System.nanoTime()
        var recorded = false
        fun recordOnce(code: Int, err: String) {
            if (recorded) return
            recorded = true
            recordUsage("chat", provider, usage, code, elapsedMs(t0), err)
        }
        try {
            while (true) {
                attempt++
                // payload 在循环内构建：降级重试必须重建（旧 payload 里的 stream_options 要被摘除）
                val inject = protocol == ApiProtocol.OPENAI && provider.id !in streamOptionsUnsupported
                val payload = when (protocol) {
                    ApiProtocol.OPENAI -> ApiClient.openAiPayload(history, reasoningEffort, webSearch, inject, provider.model)
                    ApiProtocol.ANTHROPIC -> ApiClient.anthropicPayload(provider.model, history, reasoningEffort, webSearch)
                }.put("model", provider.model) // 统一填模型名
                val request = Connection.applyAuth(Request.Builder().url(url), provider.apiKey, protocol, dialect)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val call: Call = client.newCall(request)
                // 取消即断：协程取消时从取消方立即关闭 socket，
                // 否则阻塞在 readUtf8Line 的读取线程要等到下一条数据（或 120s 读超时）才退出
                val job = coroutineContext[Job]
                val cancelHandle = job?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val err = response.body?.string()?.take(300) ?: ""
                            if (response.code in RETRYABLE_CODES && attempt < 3) {
                                // 跳出 use/try，由外层循环退避后重试
                                throw RetrySignal(response.code)
                            }
                            // 鉴权被拒且没换过方言：请求根本没被处理，换一种头重发不会重复计费
                            if (isAuthRejected(response.code) && !authTried) throw AuthFallbackSignal(response.code)
                            // 中转站不认 stream_options：摘参重试一次并记住该 Provider（Task 5 降级保险）；
                            // 此时未吐流，重发无重复计费风险。已降级过/非 OpenAI/非注入请求不进此支
                            if (inject && !downgraded && ApiClient.shouldDropStreamOptions(err)) {
                                streamOptionsUnsupported.add(provider.id)
                                downgraded = true
                                throw RetrySignal(response.code)
                            }
                            // 统一格式化：JSON 报文提取 error.message，HTML 错误页给人话提示；
                            // 保留到 300 字符：降级重试的关键词匹配需要看到报文尾部的
                            // "code":"unsupported_parameter" 等字段（截到 120 会把它截掉）
                            // 终态 HTTP 失败：真实状态码落账（重试中的不记）
                        recordOnce(response.code, formatApiError(response.code, err))
                        throw ApiException(redactSecrets(formatApiError(response.code, err), provider.apiKey))
                        }
                        val source = response.body?.source() ?: throw ApiException("空响应")
                        var done = false
                        var hasContent = false
                        // 非 blank 数据分片计数与形状指纹（仅本行零产出时记录）：
                        // 整条流零产出时用于诊断文案（见流尾 !receivedAny 检查）
                        var dataLines = 0
                        val unparsedShapes = mutableListOf<String>()

                        // 首 token 守卫（OkHttp per-read 超时实现）：旧墙钟检查只能两次读之间
                        // 执行——阻塞读期间检查不到，静默服务器实际仍要等满 120s socket 超时
                        //（守卫名不副实）；却会在「服务器活着、发过 keep-alive 注释行、首 token
                        // 慢于 5s」时被误杀（循环顶检查恰在注释行后触发）。改为按读限时：
                        // - 首个 data 行到达前，每次底层读限时 5s：静默服务器 5s 即报
                        //   FirstByteTimeout，不再干等 120s（占位气泡久挂的根源）；
                        // - 期间到达的注释/空行让下一读重获 5s：服务器有心跳就不误杀，
                        //   联网搜索/慢网关的首 token 再慢也等得到；
                        // - 首个 data 行到达后恢复 120s：正文/思考段的生成间隔本就可以远超 5s，
                        //   不能拦腰砍断。readUtf8Line 仍是阻塞读，协程取消靠 call.cancel() 掐断。
                        val readTimeout = source.timeout()
                        readTimeout.timeout(readTimeouts.firstDataMs, TimeUnit.MILLISECONDS)
                        var firstDataSeen = false

                        while (!done) {
                            coroutineContext.ensureActive()

                            try {
                                val line: String? = source.readUtf8Line()
                                when (line) {
                                    null -> break
                                    else -> {
                                        val isData = line.startsWith("data:")
                                        if (isData && !firstDataSeen) {
                                            // 首个 data 行到达：首 token 已来，恢复常规读超时
                                            firstDataSeen = true
                                            readTimeout.timeout(readTimeouts.streamMs, TimeUnit.MILLISECONDS)
                                        }
                                        if (!isData) continue
                                        val data = line.removePrefix("data:").trim()
                                        when (protocol) {
                                            ApiProtocol.OPENAI -> {
                                                if (ApiClient.parseOpenAiData(data, trackedOnDelta, usage, trackedOnImage, trackedOnToolCall)) {
                                                    done = true
                                                    hasContent = true
                                                } else if (data.isNotBlank()) {
                                                    hasContent = true
                                                    dataLines++
                                                    if (!receivedAny) {
                                                        val shape = ApiClient.chunkShape(data)
                                                        if (shape !in unparsedShapes && unparsedShapes.size < 3) unparsedShapes.add(shape)
                                                    }
                                                }
                                            }
                                            ApiProtocol.ANTHROPIC -> {
                                                ApiClient.parseAnthropicData(data, trackedOnDelta, usage, trackedOnToolCall)?.let { throw ApiException(it) }
                                                if (data.isNotBlank()) hasContent = true
                                            }
                                        }
                                    }
                                }
                            } catch (e: java.net.SocketTimeoutException) {
                                if (!hasContent) {
                                    // 首 data 行前的读超时 = 首 token 守卫触发（静默 5s）；
                                    // 之后的静默段超时（120s）按「服务器长时间没有响应」上报
                                    throw StreamErrorCode(
                                        if (firstDataSeen) StreamErrorKind.NoFirstToken else StreamErrorKind.FirstByteTimeout,
                                        ""
                                    )
                                }
                                break
                            } catch (e: java.io.IOException) {
                                // 连接中断（非读超时，如服务端 RST/代理断开）：
                                // 未收到任何内容按首 token 失败上报；已收到内容则视为流提前
                                // 结束，保留已生成的部分（与用户取消同语义）。
                                // 修复：旧实现 catch(Exception){continue}，连接断开后 readUtf8Line
                                // 每次都立即抛错 → continue → 死循环空转 IO 线程直到用户手动停止
                                if (!hasContent) {
                                    throw StreamErrorCode(StreamErrorKind.NoFirstToken, "")
                                }
                                break
                            }
                            // 其余异常不再吞掉重试：解析层自己已 catch，能到这里的只有
                            // 编程错误，交给外层统一处理（协程取消在此向上传播）
                        }

                        // End-of-stream content check: empty stream is NOT success
                        if (!hasContent) {
                            throw StreamErrorCode(StreamErrorKind.EmptyContent, "")
                        }
                        // 整条流零产出但收到了工具调用分片：网关按标准 function-calling 语义
                        // 把工具交回客户端执行并结束本轮（finish_reason=tool_calls），App 无
                        // 客户端工具执行回路，这轮必然无正文——用指明根因的报错替代笼统的
                        // 「空流+形状指纹」文案（gemini 系发附件实测命中的场景）。
                        // 文案不含「HTTP 4」等字样，避免误触发 FallbackPolicy
                        if (!receivedAny && toolCallNames.isNotEmpty()) {
                            throw StreamErrorCode(StreamErrorKind.ToolCall, toolCallNames.joinToString(","))
                        }
                        // 有数据分片但整条流零产出（正文/思考/图片全无）：不再「正常」结束——
                        // 否则上层只能显示误导性的「模型没有返回内容」。带上分片形状指纹，
                        // 网关实际回了什么形状气泡里直接可见（gemini 发附件空流实测的定位手段）
                        if (!receivedAny) {
                            val shapes = if (unparsedShapes.isEmpty()) "仅结束标记" else unparsedShapes.joinToString(" | ")
                            throw StreamErrorCode(
                                StreamErrorKind.EmptyContent,
                                "网关返回了 $dataLines 个数据分片，但都不是可识别的内容格式（形状: $shapes）"
                            )
                        }
                    }
                    // 只有真正跑完整流才记住生效方言，避免两次都 401 时把错的方言留下来
                    if (authTried) dialectByProvider[dialectKey(provider)] = dialect
                    break // 本轮完整成功，退出重试循环
                } catch (e: RetrySignal) {
                    kotlinx.coroutines.delay(if (e.code == 429) 2000L else 800L * attempt)
                } catch (e: AuthFallbackSignal) {
                    authTried = true
                    dialect = Connection.otherDialect(dialect)
                } finally {
                    cancelHandle?.dispose()
                    call.cancel()
                }
            }
            // 循环正常退出（break）= 完整成功
            recordOnce(200, "")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户取消：记 499 并继续传播（吞掉取消会卡死会话——plan Review Focus #1）
            recordOnce(499, "")
            throw e
        } catch (e: ApiException) {
            recordOnce(0, e.message ?: "")
            // 流内报错（parseOpenAiData 抛出）的消息可能回显密钥，与 HTTP 路径同标准打码；
            // redactSecrets 幂等，对 HTTP 路径已打码的消息再跑一遍无副作用
            throw ApiException(redactSecrets(e.message ?: "", provider.apiKey))
        } catch (e: StreamErrorCode) {
            // 空流/超时：记 0 并继续抛出——首 token 守卫的契约就是让 ViewModel 的
            // catch (StreamErrorCode) 用联网感知的友好文案提示；吞掉会让上层误走
            // 「流正常结束但无内容」的兜底分支，提示变成干巴巴的「模型没有返回内容」
            recordOnce(0, e.underlying?.ifBlank { e.kind.name } ?: e.kind.name)
            throw e
        } catch (e: Exception) {
            recordOnce(0, e.message ?: "")
            // 未收到任何内容（连接被拒/DNS 失败等）：必须向上抛，
            // 否则上层误走「流正常结束」分支，提示变成误导性的「模型没有返回内容」，
            // 且降级链（FallbackPolicy）也失去介入机会。
            // 已有部分内容（流中途断开）：保留已生成的部分，静默结束（与取消同语义）——
            // 向上抛错会用错误文案覆盖掉已有的正文。
            if (!receivedAny) throw e
        }
    }
    /**
     * 连接体检：先试 /models；拿不到列表再发一个极小的非流式对话请求，
     * 用「能不能拿到 2xx」验证地址+密钥+模型——部分网关不开放 /models
     * （实测 DeepSeek 的 /anthropic 网关等），不能因此误报「连接失败」。
     * 返回结构化的推断结果，UI 据此告诉用户「App 替你选了什么」。
     */
    override suspend fun probe(provider: Provider): ConnectionReport = withContext(Dispatchers.IO) {
        val protocol = detectProtocol(provider.baseUrl)
        val url = Connection.chatEndpoint(provider.baseUrl, protocol)
        if (provider.baseUrl.isBlank() || provider.apiKey.isBlank()) {
            return@withContext ConnectionReport(
                ok = false, protocol = protocol, endpoint = url,
                dialect = Connection.defaultDialect(protocol), modelCount = null,
                latencyMs = 0, error = if (provider.baseUrl.isBlank()) "缺少 Base URL" else "缺少 API Key"
            )
        }
        val models = listModels(provider)
        val dialect = models.dialect ?: dialectFor(provider, protocol)
        if (!models.ids.isNullOrEmpty()) {
            return@withContext ConnectionReport(
                ok = true, protocol = protocol, endpoint = url, dialect = dialect,
                modelCount = models.ids.size, latencyMs = models.latencyMs, error = null
            )
        }
        val payload = if (protocol == ApiProtocol.ANTHROPIC) {
            JSONObject()
                .put("model", provider.model)
                .put("max_tokens", 16)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        } else {
            JSONObject()
                .put("model", provider.model)
                .put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        }
        val t0 = System.nanoTime()
        try {
            val (response, pingedDialect) = executeWithAuthFallback(provider, url, payload.toString())
            response.use {
                val body = it.body?.string()
                // probe 的 /models 路径不计费不记录；只有这个小对话请求落一条明细日志
                //（仅用于「最近请求」展示，不计入统计口径 —— 见 UsageStore.UNCOUNTED_KINDS）
                if (it.isSuccessful) {
                    val usage = try { ApiClient.parseNonStreamingUsage(JSONObject(body ?: ""), protocol) } catch (_: Exception) { StreamUsage() }
                    recordUsage("probe", provider, usage, it.code, elapsedMs(t0), "")
                } else {
                    recordUsage("probe", provider, null, it.code, elapsedMs(t0),
                        formatApiError(it.code, body?.take(300) ?: ""))
                }
                ConnectionReport(
                    ok = it.isSuccessful,
                    protocol = protocol,
                    endpoint = url,
                    dialect = pingedDialect,
                    modelCount = if (it.isSuccessful) 0 else null,
                    latencyMs = elapsedMs(t0),
                    error = if (it.isSuccessful) null
                    else redactSecrets(formatApiError(it.code, body?.take(300) ?: ""), provider.apiKey)
                )
            }
        } catch (e: Exception) {
            recordUsage("probe", provider, null, 0, elapsedMs(t0), e.message ?: "")
            ConnectionReport(
                ok = false, protocol = protocol, endpoint = url, dialect = dialect,
                modelCount = null, latencyMs = elapsedMs(t0),
                error = redactSecrets(e.message?.take(200) ?: "网络错误", provider.apiKey)
            )
        }
    }
}
