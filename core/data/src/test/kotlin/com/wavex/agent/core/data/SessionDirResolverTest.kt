package com.wavex.agent.core.data

import com.wavex.agent.core.data.SessionDirResolver.Tier
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SessionDirResolver.tier 纯函数真值表钉子（JVM 可测，不触 Android）：
 * 30+ 且 ALL_FILES 授权 → ALL_FILES；26-29 且 WRITE 授权 → LEGACY_WRITE；其余一律 NONE。
 * 边界（25/26/29/30/33）与授权组合全部钉死，防止区间端点回归。
 */
class SessionDirResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 简报钉的三个用例（verbatim） ----

    @Test
    fun `30加且授权ALL_FILES`() {
        assertEquals(Tier.ALL_FILES, SessionDirResolver.tier(30, true, false))
    }

    @Test
    fun `26到29且WRITE授权`() {
        assertEquals(Tier.LEGACY_WRITE, SessionDirResolver.tier(28, false, true))
    }

    @Test
    fun `未授权一律NONE`() {
        assertEquals(Tier.NONE, SessionDirResolver.tier(33, false, false))
        assertEquals(Tier.NONE, SessionDirResolver.tier(27, false, false))
    }

    // ---- 边界补钉：区间端点与授权组合 ----

    @Test
    fun `30加以ALL_FILES授权为唯一判据`() {
        assertEquals(Tier.ALL_FILES, SessionDirResolver.tier(33, true, false))
        // 30+ 只认 ALL_FILES；仅 WRITE 授权不给档位
        assertEquals(Tier.NONE, SessionDirResolver.tier(30, false, true))
        assertEquals(Tier.NONE, SessionDirResolver.tier(31, false, false))
    }

    @Test
    fun `legacy区间端点26和29`() {
        assertEquals(Tier.LEGACY_WRITE, SessionDirResolver.tier(26, false, true))
        assertEquals(Tier.LEGACY_WRITE, SessionDirResolver.tier(29, false, true))
    }

    @Test
    fun `25及以下即使WRITE授权也是NONE`() {
        assertEquals(Tier.NONE, SessionDirResolver.tier(25, false, true))
        assertEquals(Tier.NONE, SessionDirResolver.tier(24, false, true))
    }

    @Test
    fun `legacy区间仅ALL_FILES授权不给档位`() {
        // 29 上 MANAGE/ALL_FILES 单独不构成档位：WRITE 才是 legacy 判据
        assertEquals(Tier.NONE, SessionDirResolver.tier(29, true, false))
    }

    /**
     * Review Focus（plan line 29）：权限升档（30+ 且 ALL_FILES）后解析到公共目录。
     * 纯 seam（无 Context）：sdkInt/授权布尔 + 目录探测，目录由测试注入
     * （生产默认 publicSessionsDir()）。ALL_FILES 档位 + 公共目录可写 → 公共目录；
     * 无档位 → 即便公共目录存在也落到应用私有 fallback。
     */
    @Test
    fun `permissionUpgradeSelectsPublicDirectory`() {
        val filesDir = tmp.newFolder("files")
        val publicDir = tmp.newFolder("public")

        // 权限升档：30+ 且 ALL_FILES 授权 → 公共目录
        assertEquals(
            publicDir,
            SessionDirResolver.resolve(30, allFilesGranted = true, writeGranted = false, filesDir = filesDir, publicDir = publicDir)
        )
        // legacy 档位（26-29 且 WRITE 授权）同样解析到公共目录
        assertEquals(
            publicDir,
            SessionDirResolver.resolve(28, allFilesGranted = false, writeGranted = true, filesDir = filesDir, publicDir = publicDir)
        )
        // 对照：无档位（NONE）→ 应用私有目录，公共目录不被采用
        assertEquals(
            File(filesDir, "sessions"),
            SessionDirResolver.resolve(30, allFilesGranted = false, writeGranted = false, filesDir = filesDir, publicDir = publicDir)
        )
    }
}
