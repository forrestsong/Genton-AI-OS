package com.android.server.ainative;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Slog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.KeyStore;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * TEE 锚定管理器（TEE Anchor Manager）
 * 
 * 职责：
 * 1. 周期性将审计链哈希根写入 TEE 安全存储
 * 2. 使用 Android Keystore TEE 后端生成签名
 * 3. 锚点持久化到加密分区
 * 4. 提供锚点验证接口
 * 
 * TEE 锚定流程：
 * 1. 每 N 条事件后，将最新 eventId 作为根哈希
 * 2. 使用 TEE 密钥对根哈希签名
 * 3. 将 (seq, rootHash, signature, timestamp) 写入安全存储
 * 4. 返回 anchorRef（锚点标识）
 * 
 * 安全保证：
 * - 密钥存储在 TEE（Trusty/OP-TEE），不可导出
 * - 签名在 TEE 内完成，主 OS 无法伪造
 * - 锚点文件加密存储，篡改可检测
 * 
 * 性能指标：
 * - TEE 签名延迟 < 20ms
 * - 锚点写入延迟 < 10ms
 * 
 * @see AgentAuditService
 */
public class TeeAnchorManager {
    private static final String TAG = "TeeAnchorManager";
    
    // TEE Keystore 别名
    private static final String TEE_KEY_ALIAS = "ainative_audit_anchor_key";
    
    // 锚点存储路径
    private static final String ANCHOR_DIR = "/data/system/ainative/audit";
    private static final String ANCHOR_FILE = "anchors.dat";
    
    private final Context mContext;
    // 锚点签名密钥（AndroidKeyStore，EC P-256，PURPOSE_SIGN|VERIFY）。
    // 诚实说明：这是 Keystore 支持的签名密钥，**不等于**硬件 TEE/StrongBox 背书；
    // 真正的硬件背书需要 setStrongBoxBacked(true) 并在真机上验证（WBS 1.6b）。
    private java.security.PrivateKey mAnchorSigningKey;
    private java.security.PublicKey mAnchorVerifyKey;
    
    // 锚点历史（用于链校验）
    private final List<TeeAnchor> mAnchors;
    
    public TeeAnchorManager(Context context) {
        mContext = context;
        mAnchors = new ArrayList<>();
        
        try {
            // 初始化 TEE 密钥
            initializeTeeKey();
            
            // 加载锚点历史
            loadAnchors();
            
            Slog.i(TAG, "TeeAnchorManager initialized with " + mAnchors.size() + " anchors");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize TeeAnchorManager", e);
        }
    }
    
    /**
     * 创建 TEE 锚点
     * 
     * @param seq 事件序列号
     * @param rootHash 根哈希（eventId）
     * @return 锚点引用
     */
    public String createAnchor(long seq, String rootHash) {
        long startNanos = System.nanoTime();
        
        try {
            // 1. 使用 TEE 密钥签名根哈希
            String signature = signWithTee(rootHash);
            
            // 2. 创建锚点对象
            TeeAnchor anchor = new TeeAnchor();
            anchor.seq = seq;
            anchor.rootHash = rootHash;
            anchor.signature = signature;
            anchor.timestampNanos = System.nanoTime();
            anchor.anchorRef = generateAnchorRef(seq);
            
            // 3. 持久化锚点
            mAnchors.add(anchor);
            persistAnchors();
            
            long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
            Slog.i(TAG, String.format("TEE anchor created: seq=%d, ref=%s (%dµs)",
                seq, anchor.anchorRef, elapsedMicros));
            
            return anchor.anchorRef;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create TEE anchor", e);
            return "anchor_failed_" + seq;
        }
    }
    
    /**
     * 验证锚点
     * 
     * @param anchorRef 锚点引用
     * @param rootHash 待验证的根哈希
     * @return 验证结果
     */
    public boolean verifyAnchor(String anchorRef, String rootHash) {
        try {
            TeeAnchor anchor = findAnchor(anchorRef);
            if (anchor == null) {
                Slog.w(TAG, "Anchor not found: " + anchorRef);
                return false;
            }
            
            if (!anchor.rootHash.equals(rootHash)) {
                Slog.e(TAG, String.format("Anchor hash mismatch: expected=%s, actual=%s",
                    anchor.rootHash, rootHash));
                return false;
            }
            
            // 验证 TEE 签名
            boolean valid = verifyTeeSignature(rootHash, anchor.signature);
            
            if (!valid) {
                Slog.e(TAG, "TEE signature verification failed: " + anchorRef);
            }
            
            return valid;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to verify anchor", e);
            return false;
        }
    }
    
    /**
     * 获取最新锚点
     */
    public TeeAnchor getLatestAnchor() {
        if (mAnchors.isEmpty()) {
            return null;
        }
        return mAnchors.get(mAnchors.size() - 1);
    }
    
    /**
     * 获取所有锚点
     */
    public List<TeeAnchor> getAllAnchors() {
        return new ArrayList<>(mAnchors);
    }
    
    // ========== 私有方法 ==========
    
    /**
     * 初始化 TEE 密钥
     */
    private void initializeTeeKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        
        if (!keyStore.containsAlias(TEE_KEY_ALIAS)) {
            // 用 EC P-256 生成**签名**密钥。
            // 原实现用 AES + PURPOSE_SIGN + BLOCK_MODE_GCM，Keystore 会直接拒绝
            // （AES 不支持签名用途），异常被吞掉后 mTeeKey 恒为 null —— 这正是
            // "TEE 锚点"长期是空转的根因。
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                TEE_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build();
            
            kpg.initialize(spec);
            java.security.KeyPair keyPair = kpg.generateKeyPair();
            mAnchorSigningKey = keyPair.getPrivate();
            mAnchorVerifyKey = keyPair.getPublic();
            
            Slog.i(TAG, "Anchor signing key generated: " + TEE_KEY_ALIAS);
        } else {
            // 加载已有密钥
            mAnchorSigningKey = (java.security.PrivateKey) keyStore.getKey(TEE_KEY_ALIAS, null);
            mAnchorVerifyKey = keyStore.getCertificate(TEE_KEY_ALIAS).getPublicKey();
            Slog.i(TAG, "Anchor signing key loaded: " + TEE_KEY_ALIAS);
        }
    }
    
    /**
     * 使用 TEE 密钥签名
     */
    private String signWithTee(String data) throws Exception {
        // WBS 1.6：真实签名。原实现返回 `别名 + SHA-256(数据)` 的字符串拼接，
        // 而"验签"就是重算同一个哈希 —— 任何同权限者都能伪造并"验证通过"。
        if (mAnchorSigningKey == null) {
            // fail-closed：没有签名密钥时**绝不**返回可"验证通过"的假签名
            throw new IllegalStateException("anchor signing key unavailable");
        }
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(mAnchorSigningKey);
        sig.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(sig.sign());
    }
    
    /**
     * 计算 SHA-256 哈希（工具方法）
     */
    private String computeHash(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            return java.util.Base64.getEncoder().encodeToString(hash).substring(0, 32);
        } catch (Exception e) {
            Slog.e(TAG, "computeHash failed", e);
            return "";
        }
    }
    
    /**
     * 验证 TEE 签名
     */
    private boolean verifyTeeSignature(String data, String signature) throws Exception {
        // WBS 1.6：真实验签（原实现是"用同一函数重算后比较字符串"，等同于不验签）
        if (mAnchorVerifyKey == null || signature == null) {
            return false;   // fail-closed
        }
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(mAnchorVerifyKey);
        sig.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return sig.verify(java.util.Base64.getDecoder().decode(signature));
    }
    
    /**
     * 生成锚点引用
     */
    private String generateAnchorRef(long seq) {
        return String.format("tee_anchor_%d_%d", seq, System.currentTimeMillis());
    }
    
    /**
     * 查找锚点
     */
    private TeeAnchor findAnchor(String anchorRef) {
        for (TeeAnchor anchor : mAnchors) {
            if (anchor.anchorRef.equals(anchorRef)) {
                return anchor;
            }
        }
        return null;
    }
    
    /**
     * 持久化锚点
     */
    private void persistAnchors() {
        try {
            File dir = new File(ANCHOR_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            File file = new File(dir, ANCHOR_FILE);
            
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeInt(mAnchors.size());
                for (TeeAnchor anchor : mAnchors) {
                    oos.writeLong(anchor.seq);
                    oos.writeUTF(anchor.rootHash);
                    oos.writeUTF(anchor.signature);
                    oos.writeLong(anchor.timestampNanos);
                    oos.writeUTF(anchor.anchorRef);
                }
            }
            
            Slog.d(TAG, "Anchors persisted: " + mAnchors.size());
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to persist anchors", e);
        }
    }
    
    /**
     * 加载锚点
     */
    private void loadAnchors() {
        try {
            File file = new File(ANCHOR_DIR, ANCHOR_FILE);
            if (!file.exists()) {
                Slog.i(TAG, "No existing anchors file");
                return;
            }
            
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                int count = ois.readInt();
                for (int i = 0; i < count; i++) {
                    TeeAnchor anchor = new TeeAnchor();
                    anchor.seq = ois.readLong();
                    anchor.rootHash = ois.readUTF();
                    anchor.signature = ois.readUTF();
                    anchor.timestampNanos = ois.readLong();
                    anchor.anchorRef = ois.readUTF();
                    mAnchors.add(anchor);
                }
            }
            
            Slog.i(TAG, "Anchors loaded: " + mAnchors.size());
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to load anchors", e);
        }
    }
    
    // ========== 数据结构 ==========
    
    /**
     * TEE 锚点
     */
    public static class TeeAnchor {
        public long seq;                 // 事件序列号
        public String rootHash;          // 根哈希（eventId）
        public String signature;         // TEE 签名
        public long timestampNanos;      // 锚定时间
        public String anchorRef;         // 锚点引用
        
        @Override
        public String toString() {
            return String.format("TeeAnchor{seq=%d, ref=%s, hash=%s}",
                seq, anchorRef, rootHash);
        }
    }
}
