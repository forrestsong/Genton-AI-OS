package com.android.server.genton;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Slog;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 记忆加密引擎
 * 
 * 核心职责：
 * 1. 分级加密记忆内容（按敏感度）
 * 2. 管理加密密钥（设备密钥/用户密钥/TEE 密钥）
 * 3. 支持解密已加密的记忆
 * 4. 密钥轮换和密钥派生
 * 
 * 加密级别：
 * - SENSITIVITY_PUBLIC (0): 不加密（明文）
 * - SENSITIVITY_INTERNAL (1): 设备密钥加密（Android Keystore）
 * - SENSITIVITY_CONFIDENTIAL (2): 用户密钥加密（需要用户认证）
 * - SENSITIVITY_SECRET (3): TEE 密钥加密（需要 TEE 后端）
 * 
 * 加密算法：
 * - AES-256-GCM（认证加密，防篡改）
 * - 12 字节 IV（随机生成）
 * - 128 位认证标签
 * 
 * 密钥存储：
 * - INTERNAL/CONFIDENTIAL: Android Keystore (StrongBox/TEE)
 * - SECRET: TEE 独占密钥
 * 
 * 对应任务：A5.1（分级加密）
 * 前置依赖：E2.2（TEE 接入评审）
 * 草案来源：D4
 * 
 * @author AI Native OS Team
 * @since A3 (智能与端云)
 */
public class MemoryEncryptionEngine {
    private static final String TAG = "MemoryEncryptionEngine";
    
    // Android Keystore 别名
    private static final String KEYSTORE_ALIAS_INTERNAL = "genton_memory_internal";
    private static final String KEYSTORE_ALIAS_CONFIDENTIAL = "genton_memory_confidential";
    private static final String KEYSTORE_ALIAS_SECRET = "genton_memory_secret";
    
    // 加密算法
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;  // 128 位认证标签
    private static final int GCM_IV_LENGTH = 12;    // 12 字节 IV
    
    private final Context mContext;
    private final KeyStore mKeyStore;
    
    public MemoryEncryptionEngine(Context context) {
        mContext = context;
        mKeyStore = loadKeyStore();
        
        // 初始化加密密钥
        try {
            ensureKey(KEYSTORE_ALIAS_INTERNAL, false);
            ensureKey(KEYSTORE_ALIAS_CONFIDENTIAL, true);
            ensureKey(KEYSTORE_ALIAS_SECRET, true);
            
            Slog.i(TAG, "MemoryEncryptionEngine initialized");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize encryption keys", e);
            throw new RuntimeException("MemoryEncryptionEngine initialization failed", e);
        }
    }
    
    /**
     * 加密内容
     * 
     * @param plaintext 明文内容
     * @param sensitivity 敏感度级别
     * @return 加密后的内容（Base64 编码）
     */
    public String encrypt(String plaintext, int sensitivity) throws Exception {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        
        // PUBLIC 级别不加密
        if (sensitivity == AgentMemoryService.SENSITIVITY_PUBLIC) {
            return plaintext;
        }
        
        // 选择密钥别名
        String keyAlias = getKeyAlias(sensitivity);
        
        // 获取密钥
        SecretKey secretKey = getSecretKey(keyAlias);
        if (secretKey == null) {
            throw new Exception("Failed to get encryption key for sensitivity: " + sensitivity);
        }
        
        // 加密
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        
        // 格式：IV (12 bytes) + Ciphertext (包含 GCM tag)
        byte[] encrypted = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, encrypted, 0, iv.length);
        System.arraycopy(ciphertext, 0, encrypted, iv.length, ciphertext.length);
        
        // Base64 编码
        String encoded = Base64.getEncoder().encodeToString(encrypted);
        
        Slog.d(TAG, String.format("Encrypted: sensitivity=%d, size=%d bytes",
            sensitivity, encrypted.length));
        
        return encoded;
    }
    
    /**
     * 解密内容
     * 
     * @param encrypted 加密内容（Base64 编码）
     * @param sensitivity 敏感度级别
     * @return 解密后的明文
     */
    public String decrypt(String encrypted, int sensitivity) throws Exception {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        
        // PUBLIC 级别未加密
        if (sensitivity == AgentMemoryService.SENSITIVITY_PUBLIC) {
            return encrypted;
        }
        
        // 选择密钥别名
        String keyAlias = getKeyAlias(sensitivity);
        
        // 获取密钥
        SecretKey secretKey = getSecretKey(keyAlias);
        if (secretKey == null) {
            throw new Exception("Failed to get decryption key for sensitivity: " + sensitivity);
        }
        
        // Base64 解码
        byte[] encryptedBytes = Base64.getDecoder().decode(encrypted);
        
        // 提取 IV 和密文
        if (encryptedBytes.length < GCM_IV_LENGTH) {
            throw new Exception("Invalid encrypted data: too short");
        }
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(encryptedBytes, 0, iv, 0, GCM_IV_LENGTH);
        
        byte[] ciphertext = new byte[encryptedBytes.length - GCM_IV_LENGTH];
        System.arraycopy(encryptedBytes, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
        
        // 解密
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
        
        byte[] plaintext = cipher.doFinal(ciphertext);
        
        String decrypted = new String(plaintext, StandardCharsets.UTF_8);
        
        Slog.d(TAG, String.format("Decrypted: sensitivity=%d, size=%d bytes",
            sensitivity, plaintext.length));
        
        return decrypted;
    }
    
    /**
     * 轮换密钥（重新加密所有记忆）
     * 
     * @param sensitivity 敏感度级别
     */
    public void rotateKey(int sensitivity) throws Exception {
        String keyAlias = getKeyAlias(sensitivity);
        
        // 删除旧密钥
        mKeyStore.deleteEntry(keyAlias);
        
        // 生成新密钥
        boolean requireAuth = (sensitivity >= AgentMemoryService.SENSITIVITY_CONFIDENTIAL);
        ensureKey(keyAlias, requireAuth);
        
        Slog.i(TAG, "Key rotated for sensitivity: " + sensitivity);
        
        // TODO: 触发记忆重新加密任务
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 加载 Android Keystore
     */
    private KeyStore loadKeyStore() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            return keyStore;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to load AndroidKeyStore", e);
            throw new RuntimeException("KeyStore initialization failed", e);
        }
    }
    
    /**
     * 确保密钥存在
     */
    private void ensureKey(String alias, boolean requireAuth) throws Exception {
        if (mKeyStore.containsAlias(alias)) {
            return;  // 密钥已存在
        }
        
        // 生成新密钥
        KeyGenerator keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        );
        
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true);
        
        // 需要用户认证的密钥
        if (requireAuth) {
            builder.setUserAuthenticationRequired(true)
                   .setUserAuthenticationValidityDurationSeconds(300);  // 5 分钟有效
        }
        
        keyGen.init(builder.build());
        keyGen.generateKey();
        
        Slog.i(TAG, String.format("Generated key: %s (requireAuth=%b)", alias, requireAuth));
    }
    
    /**
     * 获取密钥别名
     */
    private String getKeyAlias(int sensitivity) {
        switch (sensitivity) {
            case AgentMemoryService.SENSITIVITY_INTERNAL:
                return KEYSTORE_ALIAS_INTERNAL;
            case AgentMemoryService.SENSITIVITY_CONFIDENTIAL:
                return KEYSTORE_ALIAS_CONFIDENTIAL;
            case AgentMemoryService.SENSITIVITY_SECRET:
                return KEYSTORE_ALIAS_SECRET;
            default:
                return KEYSTORE_ALIAS_INTERNAL;
        }
    }
    
    /**
     * 获取 SecretKey
     */
    private SecretKey getSecretKey(String alias) {
        try {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) mKeyStore.getEntry(alias, null);
            if (entry == null) {
                Slog.w(TAG, "Key not found: " + alias);
                return null;
            }
            return entry.getSecretKey();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get secret key: " + alias, e);
            return null;
        }
    }
}
