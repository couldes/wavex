package com.wavex.agent.core.domain.port

import com.wavex.agent.core.domain.model.Provider

/**
 * 服务商设置持久化端口：SharedPreferences + 手写 JSON 的存取抽象。
 * 实现由 data 层提供（ProviderStore）；API Key 的加解密细节留在实现内部。
 */
interface ProviderSettingsRepository {
    fun loadProviders(): List<Provider>
    fun saveProviders(providers: List<Provider>)
    fun loadCurrentProviderId(): String?

    /** 按「已存的当前 id + 已加载的服务商列表」取当前服务商；未选中/已删除 → null。 */
    fun loadCurrentProvider(): Provider?
    fun saveCurrentProviderId(id: String?)
    fun loadFetchedModels(): Map<String, List<String>>
    fun saveFetchedModels(models: Map<String, List<String>>)
    fun loadThemeChoice(): String
    fun saveThemeChoice(value: String)
    fun loadReasoningEffort(): String
    fun saveReasoningEffort(value: String)
}
