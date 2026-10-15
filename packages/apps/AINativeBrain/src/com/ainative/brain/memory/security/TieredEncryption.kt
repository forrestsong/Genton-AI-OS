package com.ainative.brain.memory.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ainative.privacy.PiiMaskRules
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * C3.4 记忆抽取 - 分级加密入库规则
 * 
 * 根据记忆敏感度自动选择加密策略：
 * - 高敏感：TEE AES-256-GCM（Android Keystore TEE-backed）
 * - 中敏感：APP AES-128-GCM（App-level keystore）
 * - 低敏感：明文存储（仅记录 PII 掩码状态）
 * 
 * 敏感度判定规则：
 * - 包含中等敏感 PII（PHONE/EMAIL）→ 中敏感
 * - 包含人名/地点/时间但无 PII → 低敏感
 * - 偏好类记忆默认中敏感
 */
class TieredEncryption(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TEE_KEY_ALIAS = "tee_memory_key"
        private const val APP_KEY_ALIAS = "app_memory_key"
        
        private const val AES_GCM_TAG_LENGTH = 128
        private const val IV_LENGTH = 12
    }

    enum class SensitivityLevel {
        HIGH,       // 高敏感（暂不使用，预留给包含金融/健康 PII 的场景）
        MEDIUM,     // 中敏感（包含中等 PII 或偏好类）
        LOW         // 低敏感（仅事实，无 PII）
    }

    data class EncryptedMemory(
        val level: SensitivityLevel,
        val encrypted: Boolean,
        val content: String,          // 加密后为 Base64，明文则为原文
        val iv: String?,              // Base64 编码的 IV（仅加密时）
        val keyAlias: String?         // 密钥别名（仅加密时）
    )

    /**
     * 根据 PII 检测结果和记忆类型判定敏感度
     */
    fun determineSensitivity(
        detectedPiiCategories: List<PiiMaskRules.PiiCategory>,
        memoryType: MemoryType
    ): SensitivityLevel {
        // 中等敏感 PII 类别
        val mediumPiiCategories = setOf(
            PiiMaskRules.PiiCategory.PHONE,
            PiiMaskRules.PiiCategory.EMAIL
        )

        // 检查是否包含中等敏感 PII
        if (detectedPiiCategories.any { it in mediumPiiCategories }) {
            return SensitivityLevel.MEDIUM
        }

        // 偏好类记忆默认中敏感
        if (memoryType == MemoryType.PREFERENCE) {
            return SensitivityLevel.MEDIUM
        }

        // 事实类记忆，无 PII 时为低敏感
        return SensitivityLevel.LOW
    }

    /**
     * 加密记忆内容
     */
    fun encrypt(content: String, level: SensitivityLevel): EncryptedMemory {
        return when (level) {
            SensitivityLevel.HIGH -> encryptWithTee(content)
            SensitivityLevel.MEDIUM -> encryptWithApp(content)
            SensitivityLevel.LOW -> {
                // 低敏感记忆不加密
                EncryptedMemory(
                    level = SensitivityLevel.LOW,
                    encrypted = false,
                    content = content,
                    iv = null,
                    keyAlias = null
                )
            }
        }
    }

    /**
     * 解密记忆内容
     */
    fun decrypt(encryptedMemory: EncryptedMemory): String {
        if (!encryptedMemory.encrypted) {
            return encryptedMemory.content
        }

        val keyAlias = encryptedMemory.keyAlias
            ?: throw IllegalArgumentException("Missing key alias for encrypted memory")
        
        val iv = Base64.getDecoder().decode(encryptedMemory.iv)
        val ciphertext = Base64.getDecoder().decode(encryptedMemory.content)

        val key = loadKey(keyAlias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, StandardCharsets.UTF_8)
    }

    /**
     * TEE AES-256-GCM 加密（高敏感）
     */
    private fun encryptWithTee(content: String): EncryptedMemory {
        val key = getOrCreateKey(TEE_KEY_ALIAS, requireTee = true, keySize = 256)
        return performEncryption(content, key, TEE_KEY_ALIAS, SensitivityLevel.HIGH)
    }

    /**
     * APP AES-128-GCM 加密（中敏感）
     */
    private fun encryptWithApp(content: String): EncryptedMemory {
        val key = getOrCreateKey(APP_KEY_ALIAS, requireTee = false, keySize = 128)
        return performEncryption(content, key, APP_KEY_ALIAS, SensitivityLevel.MEDIUM)
    }

    /**
     * 执行 AES-GCM 加密
     */
    private fun performEncryption(
        content: String,
        key: SecretKey,
        keyAlias: String,
        level: SensitivityLevel
    ): EncryptedMemory {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val plaintext = content.toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)

        return EncryptedMemory(
            level = level,
            encrypted = true,
            content = Base64.getEncoder().encodeToString(ciphertext),
            iv = Base64.getEncoder().encodeToString(iv),
            keyAlias = keyAlias
        )
    }

    /**
     * 获取或创建加密密钥
     */
    private fun getOrCreateKey(
        alias: String,
        requireTee: Boolean,
        keySize: Int
    ): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        // 检查密钥是否已存在
        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as SecretKey
        }

        // 生成新密钥
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(keySize)
            .setUserAuthenticationRequired(false)

        // TEE 要求
        if (requireTee && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * 加载已有密钥
     */
    private fun loadKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        if (!keyStore.containsAlias(alias)) {
            throw IllegalStateException("Key not found: $alias")
        }

        return keyStore.getKey(alias, null) as SecretKey
    }

    enum class MemoryType {
        FACT,           // 事实类记忆
        PREFERENCE      // 偏好类记忆
    }
}
