package com.wavex.agent.core.data

import java.io.File
import java.util.UUID

/**
 * 旧 conversations.json → 逐会话 JSONL 一次性迁移（spec §7.1）：
 * 1. jsonFile 缺失 / LegacyConversationJson.parse 为空（含损坏）→ false，不动任何文件；
 * 2. 逐会话把 SessionCodec.initialLines 产出写为 `<迁移时刻+1000-序号>_<uuid8>.jsonl`
 *    （倒序赋值保序：列表第 i 个 epoch 递减，文件名降序 = 旧列表顺序 = UI 新在前）；
 * 3. 每个文件先写 `<name>.jsonl.tmp` 再原子 rename —— 崩溃至多留下无 .jsonl 后缀的 tmp 残片
 *    （loadAll 不识别，无污染），绝不会出现半截 .jsonl；
 * 4. 全部写成功后 jsonFile 原子改名为 `<jsonFile.name>.migrated`（已存在则先删；
 *    .migrated 不删除，作为手工救回通道）；
 * 5. 任一步失败 → 已写文件保留、jsonFile 不改名（下次启动重试；重试产出的同 id 文件
 *    epoch 更大，由 SessionStore.loadAll 按文件名较大者去重兜底）。
 *
 * 只写盘、不触碰 SessionStore 内存索引：迁移完成后由调用方 loadAll() 扫描重建。
 * 调用方须在会话单写者启动前调用（AppContainer 接线序，Task 7）。
 * store.dir 不存在时先 mkdirs（json 从备份恢复、sessions 目录尚未建出时也能一次迁成）。
 */
internal object LegacyMigrator {

    fun migrateIfNeeded(jsonFile: File, store: SessionStore, nowEpoch: Long,
                        now: () -> String): Boolean {
        if (!jsonFile.isFile) return false
        val raw = try {
            jsonFile.readText()
        } catch (_: Exception) {
            return false
        }
        val snapshots = LegacyConversationJson.parse(raw)
        if (snapshots.isEmpty()) return false

        val dir = store.dir
        if (!dir.isDirectory && !dir.mkdirs()) return false

        for ((i, snap) in snapshots.withIndex()) {
            // 倒序赋值保序（spec §7.1）：i=0（列表最新）epoch 最大，文件名降序首位
            val name = "${nowEpoch + 1000 - i}_${UUID.randomUUID().toString().replace("-", "").take(8)}.jsonl"
            val tmp = File(dir, "$name.tmp")
            try {
                val lines = SessionCodec.initialLines(snap, now = now)
                tmp.writeText(lines.joinToString("\n") + "\n")
                val final = File(dir, name)
                if (!tmp.renameTo(final)) {
                    // 个别文件系统 rename 到已存在目标会失败：删旧文件后重试一次
                    final.delete()
                    if (!tmp.renameTo(final)) return false
                }
            } catch (_: Exception) {
                tmp.delete()   // 半截 tmp 清掉（无 .jsonl 后缀本就不可见，只为整洁）
                return false   // 已写文件保留、jsonFile 不改名，下次启动重试
            }
        }

        // 全部写成功：json 改名 .migrated（先删可能存在的旧 .migrated）
        val migrated = File(jsonFile.parentFile, jsonFile.name + ".migrated")
        if (migrated.exists() && !migrated.delete()) return false
        return jsonFile.renameTo(migrated)
    }
}
