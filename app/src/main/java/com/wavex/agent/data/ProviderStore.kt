package com.wavex.agent.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.wavex.agent.model.ApiProtocol
import com.wavex.agent.model.ChatAttachment
import com.wavex.agent.model.ChatRequestMessage

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
    ProviderPreset("DeepSeek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner")),
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

/**
 * 服务商持久化：SharedPreferences + 手写 JSON，不引入额外依赖。
 * API Key 只保存在本机，不上传、不打日志。
 */
class ProviderStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_providers", Context.MODE_PRIVATE)

    fun loadProviders(): MutableList<Provider> {
        val list = mutableListOf<Provider>()
        val raw = prefs.getString("providers", null) ?: return list
        return try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    Provider(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "未命名"),
                        baseUrl = o.optString("baseUrl", ""),
                        apiKey = o.optString("apiKey", ""),
                        model = o.optString("model", "deepseek-chat")
                    )
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveProviders(providers: List<Provider>) {
        val array = JSONArray()
        providers.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("baseUrl", p.baseUrl)
            o.put("apiKey", p.apiKey)
            o.put("model", p.model)
            array.put(o)
        }
        prefs.edit().putString("providers", array.toString()).apply()
    }

    fun loadCurrentProviderId(): String? = prefs.getString("currentProviderId", null)

    // 思考等级 / 联网搜索是全局偏好（不绑定单个服务商）
    fun loadReasoningEffort(): String = prefs.getString("reasoningEffort", "") ?: ""

    fun saveReasoningEffort(v: String) {
        prefs.edit().putString("reasoningEffort", v).apply()
    }


    fun saveCurrentProviderId(id: String?) {
        prefs.edit().putString("currentProviderId", id).apply()
    }
}
