package com.wavex.agent.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec §7.6 / §7.7：STALE 边界与找回提示触发（ViewModel 侧纯函数的行为钉子） */
class BackupStateTest {

    @Test
    fun `isBackupStale is false at exactly 24h and true beyond it`() {
        val now = 1_791_000_000_000L
        val day = BACKUP_STALE_AFTER_MS
        assertFalse(isBackupStale(null, now, hasMeaningfulContent = true))
        assertFalse(isBackupStale(now - day, now, hasMeaningfulContent = true))      // 恰好 24h：不 STALE
        assertTrue(isBackupStale(now - day - 1, now, hasMeaningfulContent = true))   // 超 1ms 即 STALE
    }

    @Test
    fun `isBackupStale needs meaningful local content`() {
        val now = 1_791_000_000_000L
        assertFalse(isBackupStale(now - 3 * BACKUP_STALE_AFTER_MS, now, hasMeaningfulContent = false))
        assertTrue(isBackupStale(now - 3 * BACKUP_STALE_AFTER_MS, now, hasMeaningfulContent = true))
    }

    @Test
    fun `shouldOfferRecovery fires for fresh auth with valid backups only`() {
        assertTrue(shouldOfferRecovery(freshAuth = true, lastBackupAt = null, validBackupCount = 3))
        assertFalse(shouldOfferRecovery(freshAuth = true, lastBackupAt = null, validBackupCount = 0))   // 文件夹空
        assertFalse(shouldOfferRecovery(freshAuth = true, lastBackupAt = 1L, validBackupCount = 3))     // 本安装已成功备份过
        assertFalse(shouldOfferRecovery(freshAuth = false, lastBackupAt = null, validBackupCount = 3))  // 非新鲜授权
    }
}
