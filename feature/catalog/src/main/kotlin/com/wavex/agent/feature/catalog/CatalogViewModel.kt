package com.wavex.agent.feature.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavex.agent.core.domain.chat.ReasoningLevels
import com.wavex.agent.core.domain.model.Provider
import com.wavex.agent.core.domain.model.SessionStorageStatus
import com.wavex.agent.core.domain.port.ConversationRepository
import com.wavex.agent.core.domain.port.ModelService
import com.wavex.agent.core.domain.port.ProviderSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 服务商目录 + 设置域状态（Task 8）：服务商 CRUD/选中、模型页拉取、
 * 思考档位与主题偏好、会话存储状态（经 ConversationRepository.refreshStorage 端口）。
 * 只消费 domain 端口，不依赖 data/network 具体实现。
 */
class CatalogViewModel(
    private val providerSettings: ProviderSettingsRepository,
    private val conversationRepository: ConversationRepository,
    val modelService: ModelService
) : ViewModel() {

    enum class ThemeChoice { SYSTEM, LIGHT, DARK }

    // ---------- 服务商（ccswitch 式） ----------

    val providers = mutableStateListOf<Provider>()
    var currentProviderId by mutableStateOf<String?>(null)
        private set
    val currentProvider: Provider?
        get() = providers.firstOrNull { it.id == currentProviderId }

    // ---------- 模型页状态 ----------

    var modelsFetched by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var modelsLoading by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    // ---------- 偏好 ----------

    var reasoningEffort by mutableStateOf("")
        private set
    var themeChoice by mutableStateOf(ThemeChoice.SYSTEM)
        private set

    // ---------- 会话存储目录 ----------

    var sessionStorageStatus by mutableStateOf(SessionStorageStatus(false, "", false))
        private set

    private var storageRefreshJob: Job? = null

    init {
        providers.addAll(providerSettings.loadProviders())
        currentProviderId = providerSettings.loadCurrentProviderId()?.takeIf { saved ->
            providers.any { it.id == saved }
        }
        // 恢复上次拉取的模型列表：模型页首帧直接显示真实列表（与原 WavexViewModel 一致）
        modelsFetched = providerSettings.loadFetchedModels()
        // 思考档位持久化（归一化旧值）；主题持久化（损坏值回退跟随系统）
        reasoningEffort = ReasoningLevels.normalize(providerSettings.loadReasoningEffort())
        themeChoice = providerSettings.loadThemeChoice().takeIf { it.isNotBlank() }
            ?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() }
            ?: ThemeChoice.SYSTEM
    }

    fun changeThemeChoice(v: ThemeChoice) {
        themeChoice = v
        providerSettings.saveThemeChoice(v.name)
    }

    fun changeReasoningEffort(v: String) {
        reasoningEffort = v
        providerSettings.saveReasoningEffort(v)
    }

    fun selectProvider(provider: Provider) {
        currentProviderId = provider.id
        providerSettings.saveCurrentProviderId(provider.id)
    }

    fun upsertProvider(provider: Provider) {
        val isNew = providers.none { it.id == provider.id }
        if (isNew) providers.add(provider)
        else providers[providers.indexOfFirst { it.id == provider.id }] = provider
        providerSettings.saveProviders(providers)
        // 仅在新增且当前未选中时自动切换；编辑已有服务商不改变当前选中
        if (isNew && (currentProviderId == null || providers.size == 1)) selectProvider(provider)
    }

    /**
     * 复制一个服务商：Base URL / API Key / 模型原样带走，只换 id 与名称（见 [duplicateProviderName]）。
     * 落在源服务商紧下方（列表里就近可见），且不切换当前选中：
     * 复制的意图是「再存一份待改」，静默切走会让聊天页当前的模型意外变掉。
     */
    fun duplicateProvider(provider: Provider): Provider {
        val copy = provider.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = duplicateProviderName(provider.name, providers.map { it.name })
        )
        val sourceIndex = providers.indexOfFirst { it.id == provider.id }
        if (sourceIndex >= 0) providers.add(sourceIndex + 1, copy) else providers.add(copy)
        providerSettings.saveProviders(providers)
        return copy
    }

    fun removeProvider(provider: Provider) {
        providers.removeAll { it.id == provider.id }
        providerSettings.saveProviders(providers)
        if (currentProviderId == provider.id) {
            currentProviderId = providers.firstOrNull()?.id
            currentProviderId?.let { providerSettings.saveCurrentProviderId(it) }
        }
    }

    /** 更新当前服务商的模型名（模型页手动输入/列表点选） */
    fun updateModel(model: String) {
        val provider = currentProvider ?: return
        providers[providers.indexOfFirst { it.id == provider.id }] =
            provider.copy(model = model)
        providerSettings.saveProviders(providers)
    }

    /**
     * 拉取当前服务商的模型列表：成功登记并持久化（下次冷启动首帧直接显示）；
     * 失败保留原列表（调用方不改 modelsFetched），loading 复位。
     */
    fun fetchModels() {
        val p = currentProvider ?: return
        if (p.baseUrl.isBlank() || p.apiKey.isBlank()) return
        viewModelScope.launch {
            modelsLoading = modelsLoading + (p.id to true)
            val models = runCatching { modelService.fetchModels(p) }.getOrNull()
            if (models != null) {
                modelsFetched = modelsFetched + (p.id to models)
                providerSettings.saveFetchedModels(modelsFetched)
            }
            modelsLoading = modelsLoading + (p.id to false)
        }
    }

    /**
     * 重解析会话目录（MainActivity.onResume / 存储授权返回后调用）：
     * 解析、canonical 比对、moveAllTo 全在 repository.refreshStorage 内（IO 线程）。
     * 失败记日志即止（best-effort，状态保持旧值，下一轮重试）。
     */
    fun refreshSessionStorage() {
        storageRefreshJob?.cancel()
        storageRefreshJob = viewModelScope.launch {
            try {
                sessionStorageStatus = conversationRepository.refreshStorage()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 协程取消（VM 销毁）照常传播
            } catch (e: Exception) {
                // resolve/moveAllTo 的 IO 异常不上抛也不静默：记日志，状态保持旧值
                android.util.Log.w("CatalogViewModel", "session storage refresh/move failed", e)
            }
        }
    }

}

/**
 * 副本名称：先剥掉已有的「副本 / 副本 N」尾缀回到基名，再重新加「副本」（重名则「副本 2/3/…」）。
 * 不加这一步，连点两次会得到「X 副本 副本」；剥基名让反复复制始终收敛到同一族名。
 */
internal fun duplicateProviderName(source: String, existing: List<String>): String {
    val base = source.replace(Regex("\\s副本(\\s\\d+)?\\s*$"), "").trim().ifEmpty { "未命名" }
    var candidate = "$base 副本"
    var n = 2
    while (candidate in existing) {
        candidate = "$base 副本 $n"
        n++
    }
    return candidate
}
