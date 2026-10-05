package com.wavex.agent.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 自动备份的可靠落点：用户通过 SAF 选定的文件夹（如 Documents/Wavex）。
 *
 * 与应用专属外部目录（Android/data/…，卸载时被系统整体删除）不同，
 * SAF 文件夹里的文件归用户所有，卸载重装后仍在；恢复时需用户重新授权一次
 * 同一文件夹（持久化授权不跨卸载保留，这是平台行为）。
 *
 * 授权 URI 存 SharedPreferences；该 prefs 会随应用数据被清，重装后自然回到未设置状态。
 */
class SafBackupStore(private val context: Context) {

    data class BackupEntry(val uri: Uri, val name: String, val lastModified: Long = 0L)

    /** 设置页展示用：文件夹配置状态（label 查询失败/不可访问时为 null） */
    data class Status(
        val configured: Boolean,
        val accessible: Boolean,
        val label: String?,
        val lastAt: Long?
    )

    /** 单份备份的恢复预览（选择列表用）：valid=false 时后两个字段无意义 */
    data class RestorePreview(
        val name: String,
        val valid: Boolean,
        val conversationCount: Int,
        val sampleTitles: List<String>
    )

    private val prefs = context.getSharedPreferences("wavex_backup", Context.MODE_PRIVATE)

    fun configuredTree(): Uri? =
        prefs.getString(KEY_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 持久化授权是否仍在（用户可能从系统设置里撤销过） */
    fun isTreeAccessible(tree: Uri? = configuredTree()): Boolean {
        if (tree == null) return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
    }

    /**
     * 记录/更换/清除备份文件夹。uri 为 null 表示停止自动备份（文件夹内容保留）。
     * 旧授权先释放，避免多次更换后积累占满系统持久化授权上限。
     */
    fun setTree(uri: Uri?): Boolean {
        configuredTree()?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        if (uri == null) {
            prefs.edit().remove(KEY_TREE).remove(KEY_LAST_AT).apply()
            return true
        }
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // 新文件夹 = 新的备份历史：旧的上次备份时间不能继续显示
            prefs.edit().putString(KEY_TREE, uri.toString()).remove(KEY_LAST_AT).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 文件夹显示名（如 "Wavex"）；查询失败返回 null */
    fun treeLabel(tree: Uri? = configuredTree()): String? {
        if (tree == null) return null
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 自动备份：固定名单文件覆写（小 JSON，够用；写坏了恢复扫描会自动跳过）。
     * 写完读回校验：写坏/写半个的文件不能算备份成功，删残留并返回 false。
     */
    fun backup(json: String, validate: (String) -> Boolean = { it.isNotBlank() }): Boolean {
        val tree = configuredTree() ?: return false
        if (!isTreeAccessible(tree)) return false
        val ok = writeToTree(tree, AUTO_BACKUP_NAME, json, validate)
        if (ok) prefs.edit().putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
        return ok
    }

    /**
     * 导入/恢复前安全快照：把当前状态存成带时间戳的新文件（不动 auto 备份位），
     * 只保留最近 SNAPSHOT_KEEP 份，更旧的自动清理。导错文件后可从「从备份文件夹恢复」找回。
     */
    fun writeSnapshot(json: String): Boolean {
        val tree = configuredTree() ?: return false
        if (!isTreeAccessible(tree)) return false
        val ok = writeToTree(tree, snapshotFileName(System.currentTimeMillis()), json) { it.isNotBlank() }
        if (ok) pruneOldSnapshots(tree)
        return ok
    }

    /** 写入并读回校验；同名已存在则覆写，写坏删残留返回 false */
    private fun writeToTree(tree: Uri, name: String, json: String, validate: (String) -> Boolean): Boolean {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(tree)
            // 已存在同名文件则覆写，不存在才新建
            val existing = listBackups(tree).firstOrNull {
                it.name.equals(name, ignoreCase = true)
            }?.uri
            val doc = existing ?: DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId),
                "application/json",
                name
            ) ?: return false
            context.contentResolver.openOutputStream(doc, "wt")?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: run {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
                return false
            }
            readText(doc)?.let(validate) == true
        } catch (_: Exception) {
            false
        }
    }

    /** 删除超出保留数量的旧安全快照（失败静默：裁剪只是清理，不影响数据） */
    private fun pruneOldSnapshots(tree: Uri) {
        try {
            val entries = listBackups(tree)
            val byName = entries.associateBy { it.name.lowercase() }
            pruneSnapshots(entries.map { it.name }, SNAPSHOT_KEEP).forEach { name ->
                byName[name.lowercase()]?.let {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 上次成功自动备份时间（epoch 毫秒）；从未成功为 null */
    fun lastBackupAt(): Long? = prefs.getLong(KEY_LAST_AT, 0L).takeIf { it > 0L }

    /** 列出文件夹里全部 .json 备份（自动备份 + 手动导出），按文件名降序 */
    fun listBackups(tree: Uri? = configuredTree()): List<BackupEntry> {
        if (tree == null) return emptyList()
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val out = mutableListOf<BackupEntry>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".json", ignoreCase = true)) continue
                    out.add(
                        BackupEntry(
                            DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                            name,
                            c.getLong(2)
                        )
                    )
                }
            }
            out.sortByDescending { it.name }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 读取单个备份文件内容；读取失败/内容为空返回 null */
    fun readText(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }?.takeIf { s -> s.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun status(): Status {
        val tree = configuredTree() ?: return Status(false, false, null, null)
        val accessible = isTreeAccessible(tree)
        return Status(
            configured = true,
            accessible = accessible,
            label = if (accessible) treeLabel(tree) else null,
            lastAt = lastBackupAt()
        )
    }

    companion object {
        /** 自动备份在文件夹里的固定文件名 */
        const val AUTO_BACKUP_NAME = "conversations-auto.json"

        /** 安全快照文件名前缀 */
        const val SNAPSHOT_PREFIX = "conversations-pre-import-"

        /** 安全快照保留份数：更旧的在写入新快照后自动清理 */
        const val SNAPSHOT_KEEP = 3

        /**
         * 恢复候选排序（纯函数，作用于 (文件名, 修改时间)）：按文件修改时间降序（最新状态优先）；
         * 读不到时间的（lastModified=0，部分云盘提供器）回退到旧规则：auto 优先，其余按文件名降序。
         * 安全快照因此天然排在新备份之前 —— 导错了文件后立刻恢复，拿到的就是导入前状态。
         */
        fun sortRestoreCandidates(files: List<Pair<String, Long>>): List<String> =
            files.sortedWith(
                compareByDescending<Pair<String, Long>> { it.second }
                    .thenByDescending { it.first.equals(AUTO_BACKUP_NAME, ignoreCase = true) }
                    .thenByDescending { it.first }
            ).map { it.first }

        /** 安全快照文件名：时间戳入名，名字序即时间序（裁剪/恢复排序都靠它） */
        fun snapshotFileName(timestampMs: Long): String =
            SNAPSHOT_PREFIX + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date(timestampMs)) + ".json"

        /** 纯函数：只保留最新 keep 份安全快照，返回应删除的旧文件名（非快照名不动） */
        fun pruneSnapshots(names: List<String>, keep: Int): List<String> =
            names.filter { it.startsWith(SNAPSHOT_PREFIX, ignoreCase = true) && it.endsWith(".json", ignoreCase = true) }
                .sortedDescending()
                .drop(keep)

        private const val KEY_TREE = "tree_uri"
        private const val KEY_LAST_AT = "last_saf_backup_at"

        /** 恢复列表里每份备份最多展示的对话标题数 */
        const val RESTORE_TITLE_SAMPLES = 3

        /**
         * 文件级恢复预览（选择列表数据源）：files 按恢复优先级排序传入，输出顺序保持一致。
         * 每份解析一次：parse 出至少一个对话才算有效；无效文件标记 valid=false，不中断其他文件。
         * 抽成纯函数是为了钉死预览规则（有效性判定与标题截断）并可直接单测。
         */
        fun <T> buildRestorePreviews(
            files: List<Pair<String, String>>,
            parse: (String) -> List<T>,
            title: (T) -> String
        ): List<RestorePreview> =
            files.map { (name, content) ->
                val snapshots = content.takeIf { it.isNotBlank() }?.let(parse).orEmpty()
                RestorePreview(
                    name = name,
                    valid = snapshots.isNotEmpty(),
                    conversationCount = snapshots.size,
                    sampleTitles = snapshots.take(RESTORE_TITLE_SAMPLES).map(title)
                )
            }
    }
}
