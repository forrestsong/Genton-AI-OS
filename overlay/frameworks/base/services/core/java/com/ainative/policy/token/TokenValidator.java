package com.ainative.policy.token;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 能力令牌验证器（Capability Token Validator）
 * 
 * 职责：令牌"三查"验证（签名 + scope + TTL）
 * 
 * 验证流程：
 * 1. 签名验证（离线）：TokenSigner.verifySignature
 * 2. Scope 校验：scopeIntentId 必须匹配当前动作的 sourceIntentId
 * 3. TTL 过期检查：expiresAt > currentTime
 * 4. 撤销列表检查（在线）：validateToken IPC 调用
 * 
 * 性能优化：
 * - 签名验证离线（公钥内置，避免每次 IPC）
 * - 低风险令牌可缓存验证结果（秒级 TTL）
 * - 高风险令牌强制在线校验撤销状态
 * 
 * 安全约束：
 * - Fail-secure：任何验证失败 = DENY
 * - 无匹配规则/令牌 = DENY（默认最小权限）
 * - 签名伪造/过期/撤销 = DENY + 审计告警
 * 
 * @see CapabilityToken
 * @see TokenSigner
 */
public class TokenValidator {
    
    private final TokenSigner tokenSigner;
    private final TokenRevocationList revocationList;
    
    // 验证结果缓存（低风险令牌可缓存，减少 IPC）
    private final ConcurrentHashMap<String, CachedValidation> validationCache;
    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(5); // 5秒缓存
    
    public TokenValidator(TokenSigner tokenSigner, TokenRevocationList revocationList) {
        this.tokenSigner = tokenSigner;
        this.revocationList = revocationList;
        this.validationCache = new ConcurrentHashMap<>();
    }
    
    /**
     * 令牌三查验证（完整版：签名 + scope + TTL + 撤销）
     * 
     * @param token 令牌对象
     * @param scopeIntentId 当前动作的意图 ID（须匹配令牌 scope）
     * @param capability 能力名称（须匹配令牌声明）
     * @param currentTimeNanos 当前时间戳（realtime nanos）
     * @param forceOnlineCheck 是否强制在线校验撤销状态（高风险动作）
     * @return ValidationResult 验证结果
     */
    public ValidationResult validate(CapabilityToken token, String scopeIntentId,
                                    String capability, long currentTimeNanos,
                                    boolean forceOnlineCheck) {
        // 快速缓存命中（仅低风险 + 未强制在线）
        if (!forceOnlineCheck) {
            CachedValidation cached = validationCache.get(token.tokenId);
            if (cached != null && cached.isValid(currentTimeNanos)) {
                return cached.result;
            }
        }
        
        // 三查验证
        ValidationResult result = performThreeChecks(
            token, scopeIntentId, capability, currentTimeNanos
        );
        
        // 缓存验证结果（仅成功且低风险）
        if (!forceOnlineCheck && result.isValid()) {
            validationCache.put(token.tokenId, 
                new CachedValidation(result, currentTimeNanos + CACHE_TTL_NANOS));
        }
        
        return result;
    }
    
    /**
     * 执行三查验证
     */
    private ValidationResult performThreeChecks(CapabilityToken token, String scopeIntentId,
                                               String capability, long currentTimeNanos) {
        try {
            // 第一查：签名验证（离线，防伪造）
            boolean signatureValid = tokenSigner.verifySignature(
                token.tokenId,
                token.agentId,
                token.capability,
                token.scopeIntentId,
                token.expiresAtRealtimeNanos,
                token.issuerSignature
            );
            
            if (!signatureValid) {
                return ValidationResult.failure(
                    ValidationResult.REASON_SIGNATURE_INVALID,
                    "Token signature verification failed (forged or corrupted)"
                );
            }
            
            // 第二查：Scope 校验（防跨意图盗用，核心安全边界）
            if (!token.scopeIntentId.equals(scopeIntentId)) {
                return ValidationResult.failure(
                    ValidationResult.REASON_SCOPE_MISMATCH,
                    String.format("Token scope mismatch: expected=%s, actual=%s",
                        scopeIntentId, token.scopeIntentId)
                );
            }
            
            // 能力名称校验
            if (!token.capability.equals(capability)) {
                return ValidationResult.failure(
                    ValidationResult.REASON_CAPABILITY_MISMATCH,
                    String.format("Token capability mismatch: expected=%s, actual=%s",
                        capability, token.capability)
                );
            }
            
            // 第三查：TTL 过期检查
            if (currentTimeNanos >= token.expiresAtRealtimeNanos) {
                return ValidationResult.failure(
                    ValidationResult.REASON_TOKEN_EXPIRED,
                    String.format("Token expired: expiresAt=%d, current=%d",
                        token.expiresAtRealtimeNanos, currentTimeNanos)
                );
            }
            
            // 额外检查：撤销列表（在线，实时状态）
            if (revocationList.isRevoked(token.tokenId)) {
                return ValidationResult.failure(
                    ValidationResult.REASON_TOKEN_REVOKED,
                    "Token has been revoked"
                );
            }
            
            // 全部通过
            return ValidationResult.success();
        } catch (Exception e) {
            // Fail-secure: 任何异常都返回失败
            return ValidationResult.failure(
                "validation_exception",
                "Token validation failed due to exception: " + e.getMessage()
            );
        }
    }
    
    /**
     * 快速签名验证（仅验签，不检查 scope/TTL）
     * 用于域服务快速路径（scope/TTL 由调用方自行检查）
     */
    public boolean verifySignatureOnly(CapabilityToken token) {
        try {
            return tokenSigner.verifySignature(
                token.tokenId,
                token.agentId,
                token.capability,
                token.scopeIntentId,
                token.expiresAtRealtimeNanos,
                token.issuerSignature
            );
        } catch (Exception e) {
            // Fail-secure: 任何异常都返回 false
            return false;
        }
    }
    
    /**
     * 使缓存失效（策略更新/令牌撤销时调用）
     */
    public void invalidateCache(String tokenId) {
        validationCache.remove(tokenId);
    }
    
    /**
     * 清空全部缓存（策略热更新时调用）
     */
    public void clearCache() {
        validationCache.clear();
    }
    
    /**
     * 验证结果
     */
    public static class ValidationResult {
        public static final String REASON_SIGNATURE_INVALID = "signature_invalid";
        public static final String REASON_SCOPE_MISMATCH = "scope_mismatch";
        public static final String REASON_CAPABILITY_MISMATCH = "capability_mismatch";
        public static final String REASON_TOKEN_EXPIRED = "token_expired";
        public static final String REASON_TOKEN_REVOKED = "token_revoked";
        
        private final boolean valid;
        private final String reason;
        private final String detail;
        
        private ValidationResult(boolean valid, String reason, String detail) {
            this.valid = valid;
            this.reason = reason;
            this.detail = detail;
        }
        
        public static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }
        
        public static ValidationResult failure(String reason, String detail) {
            return new ValidationResult(false, reason, detail);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getReason() {
            return reason;
        }
        
        public String getDetail() {
            return detail;
        }
    }
    
    /**
     * 缓存的验证结果
     */
    private static class CachedValidation {
        final ValidationResult result;
        final long expiresAtNanos;
        
        CachedValidation(ValidationResult result, long expiresAtNanos) {
            this.result = result;
            this.expiresAtNanos = expiresAtNanos;
        }
        
        boolean isValid(long currentTimeNanos) {
            return currentTimeNanos < expiresAtNanos;
        }
    }
    
    /**
     * CapabilityToken 简化类（用于测试，实际使用 AIDL 生成的类）
     */
    public static class CapabilityToken {
        public String tokenId;
        public String agentId;
        public String capability;
        public String scopeIntentId;
        public long issuedAtRealtimeNanos;
        public long expiresAtRealtimeNanos;
        public String issuerSignature;
        public boolean revoked;
        public String policyVersion;
    }
}
