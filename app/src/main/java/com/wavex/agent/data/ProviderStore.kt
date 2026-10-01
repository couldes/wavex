package com.wavex.agent.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 服务商配置（参考 ccswitch 的设计：一个服务商 = 名称 + Base URL + API Key + 模型）。
 * 预设模板让用户尽量少填东西；中转站/自建服务直接选"自定义"。
 */
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String
)

/** 内置预设：点一下就填好 Base URL 与默认模型，用户只需要粘贴 API Key。 */
data class ProviderPreset(
    val label: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val fallbackModels: List<String>
)

val PROVIDER_PRESETS = listOf(
    // OpenAI / Claude 放最前：国际主流服务商优先展示，国内服务商与自定义依次往后
    ProviderPreset("OpenAI", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
        listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")),
    // Claude：Base URL 指向 anthropic.com 时自动切换 Anthropic 协议（/v1/messages + x-api-key）
    ProviderPreset("Claude", "Claude", "https://api.anthropic.com", "claude-sonnet-4-5",
        listOf("claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-1")),
    // DeepSeek 现行模型为 deepseek-flash / deepseek-v4-pro（2026-02 官方定价页，
    // 旧名 deepseek-chat/deepseek-reasoner 已下线），anthropic 网关同理（ApiClient.anthropicFallbackModels）
    ProviderPreset("DeepSeek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash",
        listOf("deepseek-flash", "deepseek-v4-pro")),
    ProviderPreset("Kimi", "Kimi", "https://api.moonshot.cn/v1", "kimi-k2-0711-preview",
        listOf("kimi-k2-0711-preview", "moonshot-v1-8k", "moonshot-v1-32k")),
    ProviderPreset("通义千问", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
        listOf("qwen-plus", "qwen-turbo", "qwen-max")),
    ProviderPreset("智谱 GLM", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air",
        listOf("glm-4.5-air", "glm-4-plus", "glm-4-flash")),
    // 自定义：Base URL 完全留空（用户自己填），不预设默认模型——拉到列表后自动选第一个
    ProviderPreset("自定义", "", "", "",
        listOf())
)

/** 模型页拉取结果缓存：键 = 服务商 id，值 = 上次 /models 拉到的模型名（按响应顺序）。
 *  编解码抽成纯函数：ProviderStore 依赖 Context 进不了 JVM 测试，这里单独覆盖。 */
internal fun fetchedModelsToJson(map: Map<String, List<String>>): String {
    val o = JSONObject()
    map.forEach { (id, models) ->
        val arr = JSONArray()
        models.filter { it.isNotBlank() }.forEach { arr.put(it) }
        if (arr.length() > 0) o.put(id, arr)
    }
    return o.toString()
}

internal fun fetchedModelsFromJson(raw: String): Map<String, List<String>> {
    if (raw.isBlank()) return emptyMap()
    return try {
        val o = JSONObject(raw)
        val map = LinkedHashMap<String, List<String>>()
        for (key in o.keys()) {
            val arr = o.optJSONArray(key) ?: continue
            val models = (0 until arr.length())
                .mapNotNull { i -> (arr.opt(i) as? String)?.takeIf { s -> s.isNotBlank() } }
            if (models.isNotEmpty()) map[key] = models
        }
        map
    } catch (_: Exception) {
        // 缓存损坏就当没有：模型页退回预设兜底名单并重新拉取，不影响功能
        emptyMap()
    }
}

/**
 * 服务商持久化：SharedPreferences + 手写 JSON，不引入额外依赖。
 * API Key 只保存在本机，不上传、不打日志；落盘走 SecretStore 的 Keystore 密文，
 * JSON 里只保留名称/地址/模型等非敏感配置。
 */
class ProviderStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_providers", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    // 「没读出来」不等于「用户删光了」：只有真正解析失败时才拦住空覆盖
    private var lastLoadFailed = false

    fun loadProviders(): MutableList<Provider> {
        val list = mutableListOf<Provider>()
        val raw = prefs.getString("providers", null) ?: return list
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            // 整表坏掉：原文留一份可手工恢复的副本，别让下一次保存把它冲没
            lastLoadFailed = true
            prefs.edit().putString("providers_corrupt", raw).apply()
            return list
        }
        lastLoadFailed = false
        for (i in 0 until array.length()) {
            // 逐个元素独立解析：一个坏条目不该连带丢掉其他服务商
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", UUID.randomUUID().toString())
            list.add(
                Provider(
                    id = id,
                    name = o.optString("name", "未命名"),
                    baseUrl = o.optString("baseUrl", ""),
                    apiKey = secrets.get(id) ?: o.optString("apiKey", ""),
                    model = o.optString("model", "deepseek-chat")
                )
            )
        }
        migratePlaintextKeys(list)
        return list
    }

    /** 老版本的明文 Key 就地搬进密文；成功后 JSON 里的 apiKey 字段会被清空 */
    private fun migratePlaintextKeys(providers: List<Provider>) {
        // 先只读偏好，确认真的存在未迁移的明文才碰 Keystore
        if (providers.none { it.apiKey.isNotBlank() && secrets.get(it.id) == null }) return
        if (!secrets.available) return
        saveProviders(providers)
    }

    fun saveProviders(providers: List<Provider>) {
        if (providers.isEmpty() && lastLoadFailed) return
        val array = JSONArray()
        providers.forEach { p ->
            val encrypted = p.apiKey.isBlank() || secrets.put(p.id, p.apiKey)
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("baseUrl", p.baseUrl)
            // 加密失败时退回明文：宁可暂时留在原处，也不能让用户的 Key 消失
            o.put("apiKey", if (encrypted) "" else p.apiKey)
            o.put("model", p.model)
            array.put(o)
        }
        prefs.edit().putString("providers", array.toString()).apply()
        lastLoadFailed = false
        secrets.retain(providers.mapTo(mutableSetOf()) { it.id })
    }

    fun loadCurrentProviderId(): String? = prefs.getString("currentProviderId", null)

    // 主题选择是全局偏好（跟随系统/亮色/暗色），存枚举名，读失败回退跟随系统
    fun loadThemeChoice(): String = prefs.getString("themeChoice", "") ?: ""

    fun saveThemeChoice(v: String) {
        prefs.edit().putString("themeChoice", v).apply()
    }

    // 思考等级 / 联网搜索是全局偏好（不绑定单个服务商）
    fun loadReasoningEffort(): String = prefs.getString("reasoningEffort", "") ?: ""

    fun saveReasoningEffort(v: String) {
        prefs.edit().putString("reasoningEffort", v).apply()
    }


    fun saveCurrentProviderId(id: String?) {
        prefs.edit().putString("currentProviderId", id).apply()
    }

    // ---- 模型页拉取结果缓存 ----
    // 目的：冷启动首帧直接恢复上次的列表，避免「先闪预设兜底名单、拉取完成又跳变」。
    // 拉取失败时不写入（调用方保证），坏数据在读端静默降级为空表。

    fun loadFetchedModels(): Map<String, List<String>> =
        fetchedModelsFromJson(prefs.getString("fetchedModels", null) ?: "")

    fun saveFetchedModels(map: Map<String, List<String>>) {
        prefs.edit().putString("fetchedModels", fetchedModelsToJson(map)).apply()
    }
}
