package com.wavex.agent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的本机加密存储：密钥材料留在 AndroidKeyStore（不可导出），本类只存 AES/GCM 密文。
 *
 * 不用 androidx.security 的 EncryptedSharedPreferences：它底层是同一个 Keystore，
 * 但会把整个偏好文件一起加密，密钥一旦失效就连带丢掉供应商列表；这里要保护的只有 apiKey 一个字段。
 *
 * 已知代价（有意识的取舍）：不可导出的密钥不随换机/备份恢复迁移，
 * 那种情况下密文解不开，按「密钥缺失」处理，用户重填一次 API Key，会话与供应商配置不受影响。
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Keystore 不可用（个别 ROM/工作资料场景）时为 false，调用方退回明文而不是丢数据 */
    val available: Boolean by lazy { generateKeyIfNeeded() != null }

    fun get(id: String): String? {
        val stored = prefs.getString(key(id), null) ?: return null
        val raw = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (raw.size <= IV_LENGTH) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey() ?: return null,
                GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_LENGTH))
            )
            String(cipher.doFinal(raw.copyOfRange(IV_LENGTH, raw.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            // 密钥已不存在或被系统清除：解不开就等于没有，绝不抛出去影响启动
            null
        }
    }

    /** @return 是否加密落盘成功；false 时调用方必须另作兜底，不能当作「已保存」 */
    fun put(id: String, value: String): Boolean {
        if (value.isEmpty()) {
            remove(id)
            return true
        }
        val aesKey = generateKeyIfNeeded() ?: return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, aesKey)
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            // GCM 的 IV 每次随机，与密文一起存（IV 不是秘密）
            val payload = ByteBuffer.allocate(cipher.iv.size + ct.size)
                .put(cipher.iv).put(ct).array()
            prefs.edit().putString(key(id), Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun remove(id: String) {
        prefs.edit().remove(key(id)).apply()
    }

    /** 清理已删除服务商的密文，避免留下无人认领的密钥残骸 */
    fun retain(keepIds: Set<String>) {
        val keep = keepIds.map(::key)
        val orphaned = prefs.all.keys - keep.toSet()
        if (orphaned.isEmpty()) return
        prefs.edit().apply { orphaned.forEach { remove(it) } }.apply()
    }

    private fun key(id: String) = "sk_$id"

    private fun secretKey(): SecretKey? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            .getKey(ALIAS, null) as? SecretKey
    } catch (_: Exception) {
        null
    }

    private fun generateKeyIfNeeded(): SecretKey? =
        secretKey() ?: try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val FILE = "wavex_provider_keys"
        const val ALIAS = "wavex_provider_secret"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_LENGTH = 12
    }
}
