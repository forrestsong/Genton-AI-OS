package com.ainative.policy.token;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * 能力令牌签名器（Capability Token Signer）
 * 
 * 职责：
 * 1. 生成/加载 TEE 驻留的私钥（生产环境需对接 TEE/Keystore）
 * 2. 签发令牌：生成 tokenId + 签名
 * 3. 验证签名：域服务离线验签
 * 
 * 签名算法：SHA256withRSA（RSA-2048）
 * 签名内容：tokenId + agentId + capability + scopeIntentId + expiresAt
 * 
 * 安全约束：
 * - 私钥驻 TEE（生产环境）/ Android Keystore（开发环境）
 * - 公钥随域服务签名打包
 * - 密钥轮换机制（每季度轮换 + 旧密钥保留验证窗口）
 * 
 * @see CapabilityToken
 */
public class TokenSigner {
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    
    private PrivateKey privateKey;
    private PublicKey publicKey;
    
    /**
     * 构造函数（开发环境：内存密钥；生产环境：从 TEE/Keystore 加载）
     */
    public TokenSigner(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }
    
    /**
     * 生成密钥对（仅用于开发环境测试）
     * 生产环境须对接 TEE/Android Keystore
     */
    public static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(KEY_SIZE);
        return generator.generateKeyPair();
    }
    
    /**
     * 从 Base64 编码加载密钥对
     */
    public static TokenSigner fromBase64Keys(String privateKeyBase64, String publicKeyBase64) 
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        
        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64);
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
        
        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);
        
        return new TokenSigner(privateKey, publicKey);
    }
    
    /**
     * 签发令牌：生成 tokenId + 计算签名
     * 
     * @param agentId Agent 唯一标识
     * @param capability 能力名称
     * @param scopeIntentId 作用域（意图ID）
     * @param expiresAtRealtimeNanos 过期时间戳
     * @param policyVersion 策略版本
     * @return TokenData 包含 tokenId 和 signature
     */
    public TokenData signToken(String agentId, String capability, 
                              String scopeIntentId, long expiresAtRealtimeNanos,
                              String policyVersion) 
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        
        // 1. 生成唯一令牌ID（UUID v4）
        String tokenId = UUID.randomUUID().toString();
        
        // 2. 构造签名内容（确定性拼接）
        String signaturePayload = buildSignaturePayload(
            tokenId, agentId, capability, scopeIntentId, expiresAtRealtimeNanos
        );
        
        // 3. 计算签名
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(signaturePayload.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = signature.sign();
        
        // 4. Base64 编码签名
        String signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes);
        
        return new TokenData(tokenId, signatureBase64);
    }
    
    /**
     * 验证令牌签名（域服务离线验签）
     * 
     * @param tokenId 令牌ID
     * @param agentId Agent ID
     * @param capability 能力名称
     * @param scopeIntentId 作用域
     * @param expiresAtRealtimeNanos 过期时间
     * @param signatureBase64 Base64 编码的签名
     * @return true=签名有效，false=签名无效/伪造
     */
    public boolean verifySignature(String tokenId, String agentId, String capability,
                                  String scopeIntentId, long expiresAtRealtimeNanos,
                                  String signatureBase64) {
        try {
            // 1. 重建签名内容
            String signaturePayload = buildSignaturePayload(
                tokenId, agentId, capability, scopeIntentId, expiresAtRealtimeNanos
            );
            
            // 2. 解码签名
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            
            // 3. 验证签名
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(signaturePayload.getBytes(StandardCharsets.UTF_8));
            
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            // 任何异常都视为验证失败（安全默认：fail-secure）
            return false;
        }
    }
    
    /**
     * 构造签名载荷（确定性拼接，防止参数顺序攻击）
     */
    private String buildSignaturePayload(String tokenId, String agentId, String capability,
                                        String scopeIntentId, long expiresAtRealtimeNanos) {
        // 使用 JSON-like 格式确保可读性和确定性
        return String.format(
            "{\"tokenId\":\"%s\",\"agentId\":\"%s\",\"capability\":\"%s\"," +
            "\"scopeIntentId\":\"%s\",\"expiresAt\":%d}",
            tokenId, agentId, capability, scopeIntentId, expiresAtRealtimeNanos
        );
    }
    
    /**
     * 计算签名载荷的 SHA-256 哈希（用于审计链锚定）
     */
    public static String computePayloadHash(String tokenId, String agentId, String capability,
                                           String scopeIntentId, long expiresAtRealtimeNanos) 
            throws NoSuchAlgorithmException {
        String payload = String.format(
            "{\"tokenId\":\"%s\",\"agentId\":\"%s\",\"capability\":\"%s\"," +
            "\"scopeIntentId\":\"%s\",\"expiresAt\":%d}",
            tokenId, agentId, capability, scopeIntentId, expiresAtRealtimeNanos
        );
        
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hashBytes);
    }
    
    /**
     * 导出公钥（Base64 编码，供域服务打包）
     */
    public String exportPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
    
    /**
     * 令牌数据（签发结果）
     */
    public static class TokenData {
        public final String tokenId;
        public final String signature;
        
        public TokenData(String tokenId, String signature) {
            this.tokenId = tokenId;
            this.signature = signature;
        }
    }
}
