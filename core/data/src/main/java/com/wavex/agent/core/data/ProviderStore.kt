package com.wavex.agent.core.data

import android.content.Context
import android.content.SharedPreferences
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.port.ProviderSettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
 * 服务商持久化（ProviderSettingsRepository 端口的数据层实现）：SharedPreferences + 手写 JSON，
 * 不引入额外依赖。
 * API Key 只保存在本机，不上传、不打日志；落盘走 SecretStore 的 Keystore 密文，
 * JSON 里只保留名称/地址/模型等非敏感配置。
 */
class ProviderStore(context: Context) : ProviderSettingsRepository {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_providers", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    // 「没读出来」不等于「用户删光了」：只有真正解析失败时才拦住空覆盖
    private var lastLoadFailed = false

    override fun loadProviders(): MutableList<Provider> {
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

    override fun saveProviders(providers: List<Provider>) {
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

    override fun loadCurrentProviderId(): String? = prefs.getString("currentProviderId", null)

    /** 当前服务商 = 已存的当前 id + 已加载的服务商列表（未选中/已删除 → null） */
    override fun loadCurrentProvider(): Provider? =
        loadProviders().firstOrNull { it.id == loadCurrentProviderId() }

    // 主题选择是全局偏好（跟随系统/亮色/暗色），存枚举名，读失败回退跟随系统
    override fun loadThemeChoice(): String = prefs.getString("themeChoice", "") ?: ""

    override fun saveThemeChoice(v: String) {
        prefs.edit().putString("themeChoice", v).apply()
    }

    // 思考等级 / 联网搜索是全局偏好（不绑定单个服务商）
    override fun loadReasoningEffort(): String = prefs.getString("reasoningEffort", "") ?: ""

    override fun saveReasoningEffort(v: String) {
        prefs.edit().putString("reasoningEffort", v).apply()
    }


    override fun saveCurrentProviderId(id: String?) {
        prefs.edit().putString("currentProviderId", id).apply()
    }

    // ---- 模型页拉取结果缓存 ----
    // 目的：冷启动首帧直接恢复上次的列表，避免「先闪预设兜底名单、拉取完成又跳变」。
    // 拉取失败时不写入（调用方保证），坏数据在读端静默降级为空表。

    override fun loadFetchedModels(): Map<String, List<String>> =
        fetchedModelsFromJson(prefs.getString("fetchedModels", null) ?: "")

    override fun saveFetchedModels(map: Map<String, List<String>>) {
        prefs.edit().putString("fetchedModels", fetchedModelsToJson(map)).apply()
    }
}
