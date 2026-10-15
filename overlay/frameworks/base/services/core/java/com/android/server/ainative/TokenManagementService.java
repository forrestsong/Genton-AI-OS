package com.android.server.ainative;

import android.content.Context;
import android.os.SystemClock;
import android.util.Slog;

import com.ainative.policy.CapabilityToken;
import com.ainative.policy.token.TokenSigner;
import com.ainative.policy.token.TokenValidator;
import com.ainative.policy.token.TokenRevocationList;

import java.security.KeyPair;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 令牌管理服务（A4.2 令牌服务端，被 PolicyEngineService 唯一持有与委托）
 *
 * 职责：
 * 1. 令牌签发：issueToken —— SHA256withRSA 真实签名 + 短时 TTL（按能力风险档）
 * 2. 令牌验证：validateToken —— 签名/scope/能力/TTL/撤销全查，fail-secure
 * 3. 令牌撤销：revokeToken / revokeTokensByAgent / revokeTokensByIntent
 * 4. 密钥管理：TEE 驻留私钥（生产环境）/ 内存生成（开发环境）
 * 5. 策略版本绑定：令牌携带签发时策略版本；策略热更新可选择性批量撤销
 * 6. 撤销列表 + 活跃令牌索引（统计/批量撤销）
 *
 * 安全约束：
 * - 全部签发/撤销写审计日志（A5.2 埋点接入后改走审计链）
 * - Fail-secure：任何验证异常 = DENY
 * - 时钟：统一使用单调时钟 SystemClock.elapsedRealtimeNanos()（对齐 AIDL 语义）
 *
 * 性能要求（H2.2 验收基准）：
 * - 签发延迟 P50 < 2ms（含签名计算）
 * - 验证延迟 P50 < 1ms（RSA 离线验签 + 撤销列表查询；低风险令牌带秒级缓存）
 * - 撤销延迟 P99 < 5ms（批量撤销场景）
 *
 * @see PolicyEngineService
 */
public class TokenManagementService {
    private static final String TAG = "TokenManagementService";

    // 默认令牌 TTL（30 分钟，任务级）
    private static final long DEFAULT_TOKEN_TTL_NANOS = TimeUnit.MINUTES.toNanos(30);

    // 最长令牌 TTL（1 小时，硬上限）
    private static final long MAX_TOKEN_TTL_NANOS = TimeUnit.HOURS.toNanos(1);

    // 高风险能力的短 TTL（5 分钟）
    private static final long HIGH_RISK_TOKEN_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Context mContext;
    private final TokenSigner mTokenSigner;
    private final TokenValidator mTokenValidator;
    private final TokenRevocationList mRevocationList;

    // 活跃令牌索引（tokenId -> TokenMetadata）
    private final ConcurrentHashMap<String, TokenMetadata> mActiveTokens;

    // 当前策略版本
    private volatile String mCurrentPolicyVersion;

    public TokenManagementService(Context context) {
        mContext = context;
        mRevocationList = new TokenRevocationList();

        try {
            // 生产环境：从 TEE/Android Keystore 加载私钥（E2.2 密钥基建就绪后替换 generateKeyPair）
            // 开发环境：内存生成临时密钥对
            KeyPair keyPair = TokenSigner.generateKeyPair();
            mTokenSigner = new TokenSigner(keyPair.getPrivate(), keyPair.getPublic());
            mTokenValidator = new TokenValidator(mTokenSigner, mRevocationList);

            Slog.i(TAG, "TokenManagementService initialized with key pair");
            Slog.i(TAG, "Public key (Base64): " + mTokenSigner.exportPublicKeyBase64());
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize TokenManagementService", e);
        }

        mActiveTokens = new ConcurrentHashMap<>();
        mCurrentPolicyVersion = "1.0.0"; // 默认版本；装载后由 onPolicyUpdated 同步
    }

    /**
     * 签发能力令牌（PolicyEngineService.requestCapability / reportUserDecision 调用）
     *
     * @param agentId      Agent 唯一标识
     * @param capability   能力名称
     * @param scopeIntentId 作用域（意图ID，scope 锚定）
     * @param policyVersion 签发时策略版本
     * @return 已签名的 CapabilityToken
     * @throws SecurityException 签发失败（能力未注册/签名失败）
     */
    public CapabilityToken issueToken(String agentId, String capability,
                                      String scopeIntentId, String policyVersion) {
        try {
            // 1. 确定令牌 TTL（按能力风险档：高危及关键 5 分钟，其余 30 分钟）
            int riskLevel = isHighRiskCapability(capability) ? 2 : 0;
            long ttlNanos = determineTTL(riskLevel);
            long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
            long expiresAtNanos = currentTimeNanos + ttlNanos;

            // 2. 签发令牌（签名器生成 tokenId + 真实 RSA 签名）
            TokenSigner.TokenData tokenData = mTokenSigner.signToken(
                agentId, capability, scopeIntentId, expiresAtNanos, policyVersion
            );

            // 3. 构造令牌对象（真实 AIDL 类型，时间戳=单调时钟）
            CapabilityToken token = new CapabilityToken();
            token.tokenId = tokenData.tokenId;
            token.agentId = agentId;
            token.capability = capability;
            token.scopeIntentId = scopeIntentId;
            token.issuedAtRealtimeNanos = currentTimeNanos;
            token.expiresAtRealtimeNanos = expiresAtNanos;
            token.issuerSignature = tokenData.signature;
            token.revoked = false;
            token.policyVersion = policyVersion;

            // 4. 记录活跃令牌（用于撤销和统计）
            TokenMetadata metadata = new TokenMetadata(
                token.tokenId, agentId, scopeIntentId, capability,
                currentTimeNanos, expiresAtNanos, riskLevel
            );
            mActiveTokens.put(token.tokenId, metadata);

            // 5. 审计日志（TODO: 对接审计引擎 A5.2）
            Slog.i(TAG, String.format(
                "Token issued: tokenId=%s, agentId=%s, capability=%s, scope=%s, ttl=%dms",
                token.tokenId, agentId, capability, scopeIntentId,
                TimeUnit.NANOSECONDS.toMillis(ttlNanos)
            ));

            return token;

        } catch (Exception e) {
            Slog.e(TAG, "Failed to issue token", e);
            throw new SecurityException("Token issuance failed: " + e.getMessage());
        }
    }

    /**
     * 验证能力令牌（策略引擎 validateToken 委托；全查：签名/scope/能力/TTL/撤销）
     *
     * @return true=有效，false=伪造/过期/撤销/scope 不符（fail-secure）
     */
    public boolean validateToken(CapabilityToken token, String scopeIntentId, String capability) {
        try {
            long currentTimeNanos = SystemClock.elapsedRealtimeNanos();

            // 高风险能力强制在线校验（撤销实时性）；低风险可走秒级缓存
            boolean forceOnlineCheck = isHighRiskCapability(capability);

            TokenValidator.ValidationResult result = mTokenValidator.validate(
                convertToValidatorToken(token),
                scopeIntentId,
                capability,
                currentTimeNanos,
                forceOnlineCheck
            );

            if (!result.isValid()) {
                Slog.w(TAG, String.format(
                    "Token validation failed: tokenId=%s, reason=%s, detail=%s",
                    token.tokenId, result.getReason(), result.getDetail()
                ));
            }

            return result.isValid();

        } catch (Exception e) {
            Slog.e(TAG, "Token validation error", e);
            return false; // Fail-secure
        }
    }

    /**
     * 撤销令牌（按 tokenId）
     *
     * @return true=成功撤销，false=令牌不存在/已撤销
     */
    public boolean revokeToken(String tokenId, String reason) {
        TokenMetadata metadata = mActiveTokens.get(tokenId);
        if (metadata == null) {
            return false;
        }

        long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
        boolean success = mRevocationList.revokeToken(
            tokenId,
            metadata.agentId,
            metadata.intentId,
            reason,
            "manual_revoke",
            currentTimeNanos
        );

        if (success) {
            mActiveTokens.remove(tokenId);
            mTokenValidator.invalidateCache(tokenId);

            Slog.i(TAG, String.format(
                "Token revoked: tokenId=%s, reason=%s", tokenId, reason
            ));
        }

        return success;
    }

    /**
     * 批量撤销令牌（按 agentId）
     *
     * @return 撤销的令牌数量
     */
    public int revokeTokensByAgent(String agentId, String reason) {
        long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
        int count = mRevocationList.revokeByAgent(agentId, reason, currentTimeNanos);

        // 清理活跃令牌索引
        mActiveTokens.values().removeIf(metadata ->
            metadata.agentId.equals(agentId)
        );

        // 清空验证缓存
        mTokenValidator.clearCache();

        Slog.i(TAG, String.format(
            "Tokens revoked by agent: agentId=%s, count=%d, reason=%s",
            agentId, count, reason
        ));

        return count;
    }

    /**
     * 批量撤销令牌（按 intentId）
     *
     * @return 撤销的令牌数量
     */
    public int revokeTokensByIntent(String agentId, String intentId, String reason) {
        long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
        int count = mRevocationList.revokeByIntent(agentId, intentId, reason, currentTimeNanos);

        // 清理活跃令牌索引
        mActiveTokens.values().removeIf(metadata ->
            intentId.equals(metadata.intentId)
        );

        // 清空验证缓存
        mTokenValidator.clearCache();

        Slog.i(TAG, String.format(
            "Tokens revoked by intent: agentId=%s, intentId=%s, count=%d, reason=%s",
            agentId, intentId, count, reason
        ));

        return count;
    }

    /**
     * 策略版本更新（策略热更新成功时调用；A4.2：旧版本令牌批量撤销，fail-safe）
     *
     * @param newVersion      新策略版本
     * @param revokeOldTokens 是否撤销旧版本令牌（reloadPolicySet 装载成功后置 true）
     */
    public void onPolicyUpdated(String newVersion, boolean revokeOldTokens) {
        String oldVersion = mCurrentPolicyVersion;
        mCurrentPolicyVersion = newVersion;

        if (revokeOldTokens) {
            // 撤销所有活跃令牌（旧策略裁决结果一律作废，fail-safe）
            int count = 0;
            long currentTimeNanos = SystemClock.elapsedRealtimeNanos();

            for (TokenMetadata metadata : mActiveTokens.values()) {
                mRevocationList.revokeToken(
                    metadata.tokenId,
                    metadata.agentId,
                    metadata.intentId,
                    "policy_updated",
                    "system",
                    currentTimeNanos
                );
                count++;
            }

            mActiveTokens.clear();
            mTokenValidator.clearCache();

            Slog.i(TAG, String.format(
                "Policy updated: oldVersion=%s, newVersion=%s, revokedTokens=%d",
                oldVersion, newVersion, count
            ));
        }
    }

    /**
     * 清理过期令牌（定期任务）
     */
    public void cleanupExpiredTokens() {
        long currentTimeNanos = SystemClock.elapsedRealtimeNanos();

        // 清理活跃令牌索引中的过期项
        int activeCleanedCount = (int) mActiveTokens.values().stream()
            .filter(metadata -> metadata.expiresAtNanos < currentTimeNanos)
            .peek(metadata -> mActiveTokens.remove(metadata.tokenId))
            .count();

        // 清理撤销列表中的过期记录（7 天保留期）
        int revokedCleanedCount = mRevocationList.cleanupExpiredRecords(currentTimeNanos, 7);

        Slog.i(TAG, String.format(
            "Cleanup completed: activeTokens=%d, revokedRecords=%d",
            activeCleanedCount, revokedCleanedCount
        ));
    }

    /**
     * 获取统计信息（治理/监控用）
     */
    public TokenStats getStats() {
        TokenRevocationList.RevocationStats revocationStats = mRevocationList.getStats();

        return new TokenStats(
            mActiveTokens.size(),
            revocationStats.totalRevokedTokens,
            revocationStats.totalAgents,
            revocationStats.totalIntents
        );
    }

    /**
     * 确定令牌 TTL（按风险等级）
     * 0=LOW/1=MEDIUM → 30 分钟；2=HIGH/3=CRITICAL → 5 分钟；硬上限 1 小时
     */
    private long determineTTL(int riskLevel) {
        switch (riskLevel) {
            case 3: // CRITICAL
            case 2: // HIGH
                return HIGH_RISK_TOKEN_TTL_NANOS; // 5 分钟
            case 1: // MEDIUM
            case 0: // LOW
            default:
                return DEFAULT_TOKEN_TTL_NANOS; // 30 分钟
        }
    }

    /**
     * 判断是否为高风险能力（强制在线校验撤销状态 + 短 TTL）
     */
    private boolean isHighRiskCapability(String capability) {
        // 高风险能力列表（对齐动作注册表高敏感位；策略侧判定，不受客户端自估影响）
        return capability.startsWith("agent.input_inject") ||
               capability.startsWith("agent.send_message") ||
               capability.startsWith("agent.install_app") ||
               capability.startsWith("agent.system_modify");
    }

    /**
     * 转换令牌格式（AIDL -> TokenValidator 内部模型，字段已对齐）
     */
    private TokenValidator.CapabilityToken convertToValidatorToken(CapabilityToken token) {
        TokenValidator.CapabilityToken validatorToken = new TokenValidator.CapabilityToken();
        validatorToken.tokenId = token.tokenId;
        validatorToken.agentId = token.agentId;
        validatorToken.capability = token.capability;
        validatorToken.scopeIntentId = token.scopeIntentId;
        validatorToken.issuedAtRealtimeNanos = token.issuedAtRealtimeNanos;
        validatorToken.expiresAtRealtimeNanos = token.expiresAtRealtimeNanos;
        validatorToken.issuerSignature = token.issuerSignature;
        validatorToken.revoked = token.revoked;
        validatorToken.policyVersion = token.policyVersion;
        return validatorToken;
    }

    /**
     * 令牌元数据（内部索引）
     */
    private static class TokenMetadata {
        final String tokenId;
        final String agentId;
        final String intentId;
        final String capability;
        final long issuedAtNanos;
        final long expiresAtNanos;
        final int riskLevel;

        TokenMetadata(String tokenId, String agentId, String intentId, String capability,
                     long issuedAtNanos, long expiresAtNanos, int riskLevel) {
            this.tokenId = tokenId;
            this.agentId = agentId;
            this.intentId = intentId;
            this.capability = capability;
            this.issuedAtNanos = issuedAtNanos;
            this.expiresAtNanos = expiresAtNanos;
            this.riskLevel = riskLevel;
        }
    }

    /**
     * 统计信息
     */
    public static class TokenStats {
        public final int activeTokens;
        public final int revokedTokens;
        public final int totalAgents;
        public final int totalIntents;

        public TokenStats(int activeTokens, int revokedTokens, int totalAgents, int totalIntents) {
            this.activeTokens = activeTokens;
            this.revokedTokens = revokedTokens;
            this.totalAgents = totalAgents;
            this.totalIntents = totalIntents;
        }
    }
}
