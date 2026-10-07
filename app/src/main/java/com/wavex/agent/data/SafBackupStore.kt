package com.wavex.agent.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** 备份文件夹授权级别：READ_ONLY 只供恢复/查看读取，不产生任何写入（spec §4.2） */
enum class TreeAccess { READ_ONLY, READ_WRITE }

/** 备份管线最近一次失败原因（lastError 的取值；NONE = 无错误） */
enum class BackupIssue { NONE, NOT_CONFIGURED, NOT_ACCESSIBLE, WRITE_REJECTED, VERIFY_FAILED, READ_ONLY, STALE }

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
        val lastAt: Long?,
        /** 最近一次备份管线的失败原因（status() 从 lastError / 不可访问性推出） */
        val issue: BackupIssue = BackupIssue.NONE,
        /** 文件夹里我们写入的备份份数（自动备份+安全快照；用户自己的文件不计入） */
        val versionCount: Int = 0
    )

    /** 单份备份的恢复预览（选择列表用）：valid=false 时后两个字段无意义 */
    data class RestorePreview(
        val name: String,
        val valid: Boolean,
        val conversationCount: Int,
        val sampleTitles: List<String>
    )

    private val prefs = context.getSharedPreferences("wavex_backup", Context.MODE_PRIVATE)

    private var lastError: BackupIssue = BackupIssue.NONE

    fun configuredTree(): Uri? =
        prefs.getString(KEY_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 配置的授权级别；键缺失/损坏回退 READ_WRITE（老用户向后兼容） */
    fun configuredAccess(): TreeAccess =
        prefs.getString(KEY_ACCESS, null)?.let { runCatching { TreeAccess.valueOf(it) }.getOrNull() }
            ?: TreeAccess.READ_WRITE

    /** 持久化授权是否仍在（用户可能从系统设置里撤销过）：按授权级别判定，READ_WRITE 须同时含写 */
    fun isTreeAccessible(tree: Uri? = configuredTree()): Boolean {
        if (tree == null) return false
        val needWrite = configuredAccess() == TreeAccess.READ_WRITE
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && (!needWrite || it.isWritePermission)
        }
    }

    /**
     * 记录/更换/清除备份文件夹。uri 为 null 表示停止自动备份（文件夹内容保留）。
     * access 授权级别：READ_ONLY 只取读授权，文件夹不产生任何写入（spec §4.2）。
     * 旧授权先释放，避免多次更换后积累占满系统持久化授权上限。
     * 任何一次成功调用先复位 lastError；成功落库时同时清 KEY_LAST_AT/KEY_LAST_FP
     * —— 新文件夹 = 新的备份历史（spec §6.1）。
     */
    fun setTree(uri: Uri?, access: TreeAccess = TreeAccess.READ_WRITE): Boolean {
        configuredTree()?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        lastError = BackupIssue.NONE
        if (uri == null) {
            prefs.edit()
                .remove(KEY_TREE).remove(KEY_LAST_AT).remove(KEY_LAST_FP).remove(KEY_ACCESS)
                .apply()
            return true
        }
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                if (access == TreeAccess.READ_ONLY) Intent.FLAG_GRANT_READ_URI_PERMISSION
                else Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // 新文件夹 = 新的备份历史：旧的上次备份时间/指纹不能继续生效
            prefs.edit()
                .putString(KEY_TREE, uri.toString())
                .putString(KEY_ACCESS, access.name)
                .remove(KEY_LAST_AT)
                .remove(KEY_LAST_FP)
                .apply()
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
     * 自动备份（版本化）：只新增文件，绝不覆写已有文件（不变量 1）。
     * 指纹命中 → 不写文件，只刷新「已确认备份」时刻（不变量 2 / lastAt 语义）。
     * 写完读回校验，失败只删本次新建的那份并置 VERIFY_FAILED。
     */
    fun backup(json: String, validate: (String) -> Boolean = { it.isNotBlank() }): Boolean {
        val tree = configuredTree() ?: return false
        if (!allowsWrite(configuredAccess())) { lastError = BackupIssue.READ_ONLY; return false }
        if (!isTreeAccessible(tree)) return false   // NOT_ACCESSIBLE 由 status() 从 isTreeAccessible 推出
        val fp = contentFingerprint(json)
        if (!shouldWrite(prefs.getString(KEY_LAST_FP, null), fp)) {
            prefs.edit().putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
            return true
        }
        val name = autoBackupFileName(System.currentTimeMillis(), fp)
        val ok = writeVersioned(tree, name, json, validate)
        if (ok) {
            prefs.edit().putString(KEY_LAST_FP, fp).putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
            lastError = BackupIssue.NONE
            pruneOldAutoBackups(tree)
        }
        return ok
    }

    /**
     * 导入/恢复前安全快照：把当前状态存成带时间戳的新文件（不动 auto 备份位），
     * 只保留最近 SNAPSHOT_KEEP 份，更旧的自动清理。导错文件后可从「从备份文件夹恢复」找回。
     */
    fun writeSnapshot(json: String): Boolean {
        val tree = configuredTree() ?: return false
        if (!allowsWrite(configuredAccess())) { lastError = BackupIssue.READ_ONLY; return false }
        if (!isTreeAccessible(tree)) return false
        val ok = writeToTree(tree, snapshotFileName(System.currentTimeMillis()), json) { it.isNotBlank() }
        if (ok) pruneOldSnapshots(tree)
        return ok
    }

    /** 写入并读回校验；同名已存在则覆写。失败清理只针对本次拿到的 doc：
     *  拿到 doc 之前出异常什么都不删（绝不误删既有的同名文件，spec §4.1）；
     *  拿到之后 catch 直删该 doc 的 URI，不经名字再查找。 */
    private fun writeToTree(tree: Uri, name: String, json: String, validate: (String) -> Boolean): Boolean {
        var doc: Uri? = null
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(tree)
            // 已存在同名文件则覆写，不存在才新建
            val existing = listBackups(tree).firstOrNull {
                it.name.equals(name, ignoreCase = true)
            }?.uri
            doc = existing ?: DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId),
                "application/json",
                name
            ) ?: return false
            context.contentResolver.openOutputStream(doc, "wt")?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: run {
                deleteWritten(tree, name)
                return false
            }
            if (readText(doc)?.let(validate) == true) true
            else { deleteWritten(tree, name); false }
        } catch (_: Exception) {
            doc?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            false
        }
    }

    /** 版本化写入（自动备份专用）：只 createDocument，绝不查/复用已有同名，绝不截断任何已存在文件。
     *  写完读回校验，失败只删本次新建（拿到）的那份：拿到 doc 前出异常什么都不删（绝不误删
     *  同秒同指纹的既有旧版本，spec §4.1）；catch 直接删该 doc 的 URI，不经名字再查找。 */
    private fun writeVersioned(tree: Uri, name: String, json: String, validate: (String) -> Boolean): Boolean {
        var doc: Uri? = null
        return try {
            doc = DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                "application/json", name
            ) ?: run { lastError = BackupIssue.WRITE_REJECTED; return false }
            context.contentResolver.openOutputStream(doc, "wt")?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: run { lastError = BackupIssue.WRITE_REJECTED; deleteWritten(tree, name); return false }
            if (readText(doc)?.let(validate) == true) true
            else { lastError = BackupIssue.VERIFY_FAILED; deleteWritten(tree, name); false }
        } catch (_: Exception) {
            lastError = BackupIssue.WRITE_REJECTED
            doc?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            false
        }
    }

    /** 写失败/校验失败的清理：经 onVerifyFailed 决策，只删本次写入的那个名字，绝不动别的文件（spec §7.8） */
    private fun deleteWritten(tree: Uri, writtenName: String) {
        try {
            val entries = listBackups(tree)
            onVerifyFailed(writtenName, entries.map { it.name }).forEach { victim ->
                entries.firstOrNull { it.name.equals(victim, ignoreCase = true) }?.let {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) }
                }
            }
        } catch (_: Exception) { }
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

    /** 删除超出保留规则的旧版本化自动备份（失败静默：裁剪只是清理，不影响数据） */
    private fun pruneOldAutoBackups(tree: Uri) {
        try {
            val entries = listBackups(tree)
            val byName = entries.associateBy { it.name.lowercase() }
            pruneAutoBackups(entries.map { it.name }).forEach { name ->
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
            lastAt = lastBackupAt(),
            issue = if (!accessible) BackupIssue.NOT_ACCESSIBLE else lastError,
            versionCount = countOwnBackups(listBackups(tree).map { it.name })
        )
    }

    companion object {
        /** 自动备份在文件夹里的固定文件名 */
        const val AUTO_BACKUP_NAME = "conversations-auto.json"

        /** 安全快照文件名前缀 */
        const val SNAPSHOT_PREFIX = "conversations-pre-import-"

        /** 安全快照保留份数：更旧的在写入新快照后自动清理 */
        const val SNAPSHOT_KEEP = 3

        /** 版本化自动备份文件名前缀：时间戳入名，名字序即时间序（spec §3.1） */
        const val AUTO_PREFIX = "conversations-auto-"

        /** 版本化自动备份保留规则：最近份数上限 / 每日一份的保留天数 */
        const val AUTO_KEEP_RECENT = 20
        const val AUTO_KEEP_DAYS = 30

        /** 版本化自动备份文件名：conversations-auto-yyyyMMdd-HHmmss-<指纹8>.json */
        fun autoBackupFileName(timestampMs: Long, fingerprint: String): String =
            AUTO_PREFIX + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date(timestampMs)) + "-" + fingerprint + ".json"

        /** 内容指纹：SHA-256 hex 前 8 位。只作变更判定与文件名去歧义，不作完整性校验（spec §3.1） */
        fun contentFingerprint(json: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(8)

        /**
         * 纯函数：版本化自动备份双规则裁剪，返回应删除的文件名。
         * 候选 = AUTO_PREFIX 前缀 + .json 结尾 + 前缀后紧跟可解析的 yyyyMMdd-HHmmss（提供器重命名的
         * " (1)" 等后缀不破坏解析）；其余名字（旧固定名/快照/恢复种子/第三方/不可解析）永不进入返回列表。
         * 幸存 = 时间戳 ≥ nowMs - keepDays × 86400000 ∧ 当日（时间戳的 yyyyMMdd）最新一份 ∧ 候选内名次 < keepRecent；
         * 名次只在候选集内排：按解析时间戳降序、平级名字降序；非候选名不参与排名、不占 keepRecent 名额，
         * 因此 keepRecent 始终按「可解析的版本化份数」计——第三方/不可解析名再多也不会挤掉真正的备份。
         */
        fun pruneAutoBackups(
            names: List<String>,
            keepRecent: Int = AUTO_KEEP_RECENT,
            keepDays: Int = AUTO_KEEP_DAYS,
            nowMs: Long = System.currentTimeMillis()
        ): List<String> {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            fun timestamp(name: String): Long? {
                if (!name.startsWith(AUTO_PREFIX, ignoreCase = true)) return null
                if (!name.endsWith(".json", ignoreCase = true)) return null
                val body = name.substring(AUTO_PREFIX.length, name.length - ".json".length)
                val head = Regex("^\\d{8}-\\d{6}").find(body)?.value ?: return null
                return runCatching { stamp.parse(head)?.time }.getOrNull()
            }

            val ts = names.map { timestamp(it) }
            // 名次只在候选集内排：按解析时间戳降序，平级名字降序；非候选名不参排名、不占 keepRecent 名额
            val ranked = names.withIndex()
                .mapNotNull { (idx, name) -> ts[idx]?.let { Triple(it, name, idx) } }
                .sortedWith(compareByDescending<Triple<Long, String, Int>> { it.first }.thenByDescending { it.second })
            val cutoff = nowMs - keepDays * 86_400_000L
            val daySeen = mutableSetOf<String>()
            val keepIdx = mutableSetOf<Int>()
            for ((rank, entry) in ranked.withIndex()) {
                val (t, _, idx) = entry
                // 降序遍历中该日首次出现即当日最新；即使它因名额被删，同日更旧的一份也不能顶替幸存
                val newestOfDay = daySeen.add(dayKey.format(java.util.Date(t)))
                if (rank < keepRecent && t >= cutoff && newestOfDay) keepIdx.add(idx)
            }
            return names.mapIndexedNotNull { idx, name ->
                if (ts[idx] != null && idx !in keepIdx) name else null
            }
        }

        /** 指纹去重（spec §3.1 不变量 2）：内容没变不产生新版本 */
        fun shouldWrite(lastFp: String?, newFp: String): Boolean = lastFp != newFp

        /** 只读授权拒绝一切写入（spec §4.2） */
        fun allowsWrite(access: TreeAccess): Boolean = access == TreeAccess.READ_WRITE

        /** 验证失败后的删除决策（spec §4.1/§7.8）：只删本次写入的名字（大小写不敏感，返回真实大小写），绝不删别的文件 */
        fun onVerifyFailed(createdName: String, existingNames: List<String>): List<String> =
            existingNames.filter { it.equals(createdName, ignoreCase = true) }

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

        /** 我们的备份文件计数（spec §7.9）：自动备份（固定名+版本名，"conversations-auto" 开头全覆盖）+ 安全快照；
         *  用户自己的文件不计入「保留的备份数」 */
        fun countOwnBackups(names: List<String>): Int = names.count {
            val lower = it.lowercase()
            lower.startsWith(AUTO_BACKUP_NAME.removeSuffix(".json")) || lower.startsWith(SNAPSHOT_PREFIX)
        }

        private const val KEY_TREE = "tree_uri"
        private const val KEY_LAST_AT = "last_saf_backup_at"
        private const val KEY_ACCESS = "tree_access"
        private const val KEY_LAST_FP = "last_backup_fp"

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
