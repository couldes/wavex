package com.wavex.agent.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * 会话落盘目录解析（Task 5）：档位真值表是纯函数（JVM 可测），
 * Environment/权限探测全部收在薄胶水里，Task 7 调 [resolve]。
 *
 * 档位真值表：30+ 且 ALL_FILES 授权 → ALL_FILES；26-29 且 WRITE 授权 → LEGACY_WRITE；
 * 其余（含 25 及以下、30+ 仅 WRITE、26-29 仅 MANAGE、无授权）一律 NONE。
 */
internal object SessionDirResolver {

    enum class Tier { NONE, LEGACY_WRITE, ALL_FILES }

    /** 纯函数（JVM 可测）：sdkInt/授权布尔 → 档位 */
    fun tier(sdkInt: Int, allFilesGranted: Boolean, writeGranted: Boolean): Tier = when {
        sdkInt >= 30 && allFilesGranted -> Tier.ALL_FILES
        sdkInt in 26..29 && writeGranted -> Tier.LEGACY_WRITE
        else -> Tier.NONE
    }

    /** 公共目录 Documents/Wavex/sessions */
    fun publicSessionsDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "Wavex/sessions"
        )

    fun fallbackSessionsDir(context: Context): File = File(context.filesDir, "sessions")

    /** tier + 公共目录可写性探测；NONE 或公共不可写 → fallback（mkdirs 兜底） */
    fun resolve(context: Context): File {
        val sdkInt = Build.VERSION.SDK_INT
        val allFilesGranted = sdkInt >= 30 && Environment.isExternalStorageManager()
        val writeGranted = context.checkSelfPermission(
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return resolve(sdkInt, allFilesGranted, writeGranted, context.filesDir)
    }

    /**
     * 纯 seam（JVM 可测，无 Context）：sdkInt/授权布尔 → 档位 → 目录解析。
     * publicDir 可注入（测试传临时目录；生产默认 [publicSessionsDir]）。
     * tier=NONE 或公共目录不可写 → filesDir/sessions（mkdirs 兜底）。
     */
    fun resolve(
        sdkInt: Int,
        allFilesGranted: Boolean,
        writeGranted: Boolean,
        filesDir: File,
        publicDir: File = publicSessionsDir(),
    ): File {
        val publicUsable = tier(sdkInt, allFilesGranted, writeGranted) != Tier.NONE &&
            publicDir.isWritableAfterMkdirs()
        if (publicUsable) return publicDir
        return File(filesDir, "sessions").apply { mkdirs() }
    }

    // ---- 以下为 Android 探测胶水（保持极薄） ----

    /** mkdirs()（已存在返回 false，无妨）后以 canWrite() 探测真实可写性 */
    private fun File.isWritableAfterMkdirs(): Boolean {
        mkdirs()
        return isDirectory && canWrite()
    }
}
