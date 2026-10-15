package com.android.server.genton;

import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;

import com.genton.policy.IPolicyEngine;
import com.genton.policy.IPolicyListener;
import com.genton.policy.IntentEvaluationRequest;
import com.genton.policy.CapabilityRequest;
import com.genton.policy.PolicyDecision;
import com.genton.policy.CapabilityToken;
import com.genton.policy.PolicyRuleView;
import com.genton.policy.PolicyStats;
import com.genton.policy.TaintMapEntry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 策略引擎服务端（A7.1 策略引擎服务端）
 * 
 * 职责：
 * - 策略编译与持有（策略即代码，开机 fail-stop 校验）
 * - 意图级预检 + 动作级令牌签发
 * - 确认决策回灌
 * - 域服务令牌三查（签名 + scope + 撤销）
 * - 策略热更新
 * 
 * 核心原则：
 * 1. 确定性闸门（不含任何模型调用，100% 可单测）
 * 2. DENY 永远优先于 ALLOW
 * 3. 未知即拒（capability/动作未注册 = DENY）
 * 4. 基线策略不可覆盖
 * 
 * 两阶段求值：
 * - Phase 1: 意图级预检（evaluateIntent）
 * - Phase 2: 动作级令牌签发（requestCapability）
 * 
 * @see com.genton.policy.IPolicyEngine
 */
public class PolicyEngineService extends IPolicyEngine.Stub {
    
    /**
     * 单点鉴权（WBS 1.3）：策略引擎是信任根（裁决、令牌签发/撤销、策略包重载）。
     * 此前全部方法零调用方校验，任意进程可 reloadPolicySet / revokeTokens / reportUserDecision。
     */
    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws android.os.RemoteException {
        AgentCallerAuth.enforceTransaction(mContext, AgentCallerAuth.PERM_BIND_POLICY,
                "PolicyEngineService", code);
        return super.onTransact(code, data, reply, flags);
    }
    
    private static final String TAG = "PolicyEngine";
    
    // ==================== 常量（对齐 AIDL）====================
    public static final int STATUS_OK = 0;
    public static final int STATUS_POLICY_DENY = 1;
    public static final int STATUS_UNKNOWN_CAP = 2;
    public static final int STATUS_INVALID_REQ = 3;
    public static final int STATUS_TOKEN_INVALID = 4;
    public static final int STATUS_CONFIRM_PENDING = 5;
    public static final int STATUS_INTERNAL_ERROR = -1;
    
    public static final int DEC_GRANT = 0;
    public static final int DEC_DENY = 1;
    public static final int DEC_REQUIRE_CONFIRMATION = 2;
    public static final int DEC_REQUIRE_MITIGATION = 3;
    
    public static final int USER_ALLOW_ONCE = 0;
    public static final int USER_ALLOW_TASK = 1;
    public static final int USER_DENY = 2;
    
    // ==================== Taint 信任等级 ====================
    public static final int TRUST_SYSTEM = 0;
    public static final int TRUST_USER = 1;
    public static final int TRUST_APP_CONTENT = 2;
    public static final int TRUST_WEB_CONTENT = 3;
    
    // ==================== A4.2 令牌/确认常量 ====================
    
    /** confirmId 有效期（E1.1：确认超时后动作不得执行） */
    private static final long CONFIRM_ID_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);
    
    // ==================== 状态管理 ====================
    
    private final Context mContext;
    
    /** 当前策略版本 */
    private volatile String mPolicyVersion = "1.0.0";
    
    /** 策略加载时间 */
    private volatile long mPolicyLoadedAtNanos;
    
    /** 已注册能力白名单（能力注册表） */
    private final Set<String> mRegisteredCapabilities = new HashSet<>();
    
    /** 令牌服务端（A4.2：签发/验证/撤销/统计唯一入口；真实签名 + 短时 TTL + 撤销列表） */
    private final TokenManagementService mTokenService;
    
    /** confirmId HMAC 密钥（E1.1：confirmId 由引擎 HMAC 签发；生产入 TEE，开发内存随机） */
    private final byte[] mConfirmKey = new byte[32];
    
    /** 挂起的确认请求（rawId → PendingConfirmation） */
    private final Map<String, PendingConfirmation> mPendingConfirmations = new ConcurrentHashMap<>();
    
    /** 用户授权记忆（agentId:capability → UserAuthzState） */
    private final Map<String, UserAuthzState> mUserAuthorizations = new ConcurrentHashMap<>();
    
    /** 策略规则集（简化版，v1 实现，授权轴） */
    private final List<PolicyRule> mPolicyRules = new ArrayList<>();
    
    /** 监听器列表 */
    private final RemoteCallbackList<IPolicyListener> mListeners = new RemoteCallbackList<>();
    
    /** 规则求值器（A7.1：路由域双缓冲 + trie 匹配 + 命中统计） */
    private final PolicyRuleEvaluator mEvaluator = new PolicyRuleEvaluator();
    
    /** 装载口子运行时状态（reloadPolicySet 结果细节，供日志/审计） */
    private final Map<String, String> mLastLoadError = new ConcurrentHashMap<>();
    
    /** 用户规则装载点（装载点③，多装载点之一；不存在则跳过） */
    private static final String USER_RULES_MOUNT_PATH = "/data/misc/genton/user_policy_pack.json";
    
    // ==================== 统计 ====================
    private final AtomicLong mTotalEvaluations = new AtomicLong(0);
    private final AtomicLong mGrantCount = new AtomicLong(0);
    private final AtomicLong mDenyCount = new AtomicLong(0);
    private final AtomicLong mConfirmationCount = new AtomicLong(0);
    private final AtomicLong mMitigationCount = new AtomicLong(0);
    
    // ==================== 内部数据结构 ====================
    
    /**
     * 挂起的确认请求
     */
    private static class PendingConfirmation {
        String confirmId;
        CapabilityRequest request;
        long createdAtNanos;
        
        PendingConfirmation(String confirmId, CapabilityRequest request) {
            this.confirmId = confirmId;
            this.request = request;
            this.createdAtNanos = System.nanoTime();
        }
    }
    
    /**
     * 用户授权状态
     */
    private static class UserAuthzState {
        String agentId;
        String capability;
        int scope;  // 0=once, 1=task
        String scopeIntentId;  // task 级授权的 intentId
        long grantedAtNanos;
        
        UserAuthzState(String agentId, String capability, int scope, String intentId) {
            this.agentId = agentId;
            this.capability = capability;
            this.scope = scope;
            this.scopeIntentId = intentId;
            this.grantedAtNanos = System.nanoTime();
        }
    }
    
    /**
     * 策略规则（简化版，v1 实现）
     */
    private static class PolicyRule {
        String id;
        boolean baseline;
        String capability;
        String actionId;
        String effect;  // allow/deny/require_confirmation/require_mitigation
        int priority;
        int hitCount;
        long lastHitNanos;
        
        // Taint 约束（参数来源要求）
        Map<String, Integer> argSourceRequirements;  // argKey → required trust level
        
        PolicyRule(String id, boolean baseline, String capability, String effect, int priority) {
            this.id = id;
            this.baseline = baseline;
            this.capability = capability;
            this.effect = effect;
            this.priority = priority;
            this.hitCount = 0;
            this.argSourceRequirements = new HashMap<>();
        }
    }
    
    // ==================== 构造与初始化 ====================
    
    public PolicyEngineService(Context context) {
        mContext = context;
        mPolicyLoadedAtNanos = System.nanoTime();
        
        // A4.2：令牌服务端（RSA-2048 开发密钥；生产 E2.2 TEE/Keystore 加载）
        mTokenService = new TokenManagementService(context);
        new SecureRandom().nextBytes(mConfirmKey);
        
        // 初始化默认能力白名单
        initializeCapabilityRegistry();
        
        // 加载默认策略集（授权轴 v1）
        loadDefaultPolicies();
        
        // A7.1 装载点①：开机平台基线（fail-stop，失败即启动失败）
        loadBootDomainBaseline();
        
        // A7.1 装载点③：用户规则装载点（文件存在则热装载；不存在静默跳过）
        loadUserRulesMount();
        
        Slog.i(TAG, "PolicyEngineService initialized, version=" + mPolicyVersion);
    }
    
    // ==================== A7.1 装载口子 ====================
    
    /**
     * 装载点①：开机平台基线内置包（代码内可信源，fail-stop 语义）。
     * 事实源 PolicyBootBaseline 对齐 policy_catalog_v1.yaml sys.* 路由轴基线。
     * 失败直接抛异常 → system_server 启动失败，符合「策略即代码，开机 fail-stop 校验」。
     */
    private void loadBootDomainBaseline() {
        PolicyFileParser.LoadResult baseline =
                PolicyFileParser.parsePolicyPack(PolicyBootBaseline.PACK_JSON, "platform");
        if (!baseline.success) {
            mLastLoadError.put("boot", baseline.errors.toString());
            throw new IllegalStateException("A7.1 boot policy baseline fail-stop: "
                    + baseline.errors);
        }
        for (Map.Entry<String, List<CompiledPolicyRule>> e : baseline.byDomain.entrySet()) {
            mEvaluator.loadDomainSet(e.getKey(), e.getValue(), baseline.version);
        }
        mPolicyVersion = baseline.version;
        Slog.i(TAG, "Boot baseline loaded: domains=" + baseline.byDomain.keySet()
                + ", rules=" + baseline.ruleCount + ", version=" + baseline.version);
    }
    
    /**
     * 装载点③：用户规则装载点。
     * D6.2/传输层把用户规则包 push 到 {@link #USER_RULES_MOUNT_PATH}，
     * 引擎启动/重启时自动装载（source=user，双缓冲原子换入）。
     * 装载失败仅记录错误、不影响开机（用户规则非门禁）。
     */
    private void loadUserRulesMount() {
        File mount = new File(USER_RULES_MOUNT_PATH);
        if (!mount.exists() || !mount.isFile()) {
            return;  // 未投放用户规则，跳过
        }
        String packJson = readTextFile(mount);
        if (packJson == null) {
            mLastLoadError.put("user-mount", "read_failed:" + USER_RULES_MOUNT_PATH);
            return;
        }
        int status = doReload(packJson, "user");
        if (status == STATUS_OK) {
            Slog.i(TAG, "User rules mount loaded: " + USER_RULES_MOUNT_PATH);
        } else {
            Slog.w(TAG, "User rules mount rejected: " + mLastLoadError.get("reload"));
        }
    }
    
    /** 读小文本文件（<64KB）；失败返回 null。 */
    private static String readTextFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 256 * 1024) {
                    return null;  // 防超限
                }
            }
            return out.toString("UTF-8");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
    
    /**
     * 通用热装载（reloadPolicySet 与用户装载点共用）：
     * 解析 → 验签/源标记 → 合并保基线 → 双缓冲原子换入；任一步失败保留旧档。
     */
    private int doReload(String packJson, String expectedSource) {
        mLastLoadError.remove("reload");
        try {
            PolicyFileParser.LoadResult result =
                    PolicyFileParser.parsePolicyPack(packJson, expectedSource);
            if (!result.success) {
                mLastLoadError.put("reload", "parse_failed: " + result.errors);
                return STATUS_INVALID_REQ;
            }
            
            // 验签（E2.2 前置）：platform 源 fail-closed；user 源不签名（内容校验兜底）
            if ("platform".equals(result.source)) {
                if (!PolicyFileParser.isSignatureVerifierReady()
                        || !PolicyFileParser.verifyPackSignature(packJson, result.source)) {
                    mLastLoadError.put("reload", "signature_required: platform OTA 装载需 E2.2 密钥验签");
                    Slog.w(TAG, "reloadPolicySet rejected: platform pack requires E2.2 signature verifier");
                    return STATUS_INVALID_REQ;
                }
            }
            
            // 合并保基线：仅替换同 source 的既有规则；sys 基线(platform)与 user 规则互不覆盖
            for (Map.Entry<String, List<CompiledPolicyRule>> e : result.byDomain.entrySet()) {
                String domain = e.getKey();
                List<CompiledPolicyRule> merged = new ArrayList<>();
                for (CompiledPolicyRule existing : mEvaluator.getActiveRules(domain)) {
                    if (!existing.source.equals(result.source)) {
                        merged.add(existing);
                    }
                }
                merged.addAll(e.getValue());
                if (!mEvaluator.loadDomainSet(domain, merged, result.version)) {
                    mLastLoadError.put("reload", "swap_failed: domain=" + domain);
                    return STATUS_INVALID_REQ;
                }
            }
            
            mPolicyVersion = result.version;
            mPolicyLoadedAtNanos = System.nanoTime();
            
            // A4.2：策略版本切换 → 撤销全部旧版本令牌（旧裁决一律作废，fail-safe）
            mTokenService.onPolicyUpdated(result.version, true);
            
            // 通知监听器（订阅式推送，策略已热换入）
            notifyPolicyBundleUpdated(result.version);
            Slog.i(TAG, "Policy set reloaded: source=" + result.source
                    + ", version=" + result.version
                    + ", domains=" + result.byDomain.keySet()
                    + ", rules=" + result.ruleCount);
            return STATUS_OK;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error reloading policy set", e);
            mLastLoadError.put("reload", "internal: " + e.getMessage());
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    /**
     * 初始化能力注册表（硬编码 v1）
     */
    private void initializeCapabilityRegistry() {
        mRegisteredCapabilities.add("agent.input_inject");
        mRegisteredCapabilities.add("agent.launch_activity");
        mRegisteredCapabilities.add("agent.manage_notifications");
        mRegisteredCapabilities.add("agent.clipboard_access");
        mRegisteredCapabilities.add("agent.telephony_control");
        mRegisteredCapabilities.add("agent.device_control");
        mRegisteredCapabilities.add("agent.system_internal");
        mRegisteredCapabilities.add("agent.query_content");
        mRegisteredCapabilities.add("agent.send_message");
        mRegisteredCapabilities.add("agent.camera_access");
        mRegisteredCapabilities.add("agent.location_access");
        
        Slog.d(TAG, "Capability registry initialized: " + mRegisteredCapabilities.size() + " capabilities");
    }
    
    /**
     * 加载默认策略集（简化版 v1）
     */
    private void loadDefaultPolicies() {
        // 基线策略：高风险动作强制确认
        PolicyRule baselineConfirm = new PolicyRule(
            "sys.baseline.high_risk_confirm",
            true,  // baseline
            "*",  // 所有能力
            "require_confirmation",
            1000  // 最高优先级
        );
        mPolicyRules.add(baselineConfirm);
        
        // 基线策略：ui.type 的 text 参数必须为 USER 来源
        PolicyRule baselineTaint = new PolicyRule(
            "sys.baseline.ui_type_user_source",
            true,
            "agent.input_inject",
            "require_mitigation",
            900
        );
        baselineTaint.actionId = "ui.type";
        baselineTaint.argSourceRequirements.put("text", TRUST_USER);
        mPolicyRules.add(baselineTaint);
        
        // 默认策略：输入注入需确认
        PolicyRule inputConfirm = new PolicyRule(
            "sys.default.input_confirm",
            false,
            "agent.input_inject",
            "require_confirmation",
            100
        );
        mPolicyRules.add(inputConfirm);
        
        // 默认策略：通知管理允许
        PolicyRule notificationAllow = new PolicyRule(
            "sys.default.notification_allow",
            false,
            "agent.manage_notifications",
            "allow",
            50
        );
        mPolicyRules.add(notificationAllow);
        
        // 默认策略：查询内容允许
        PolicyRule queryAllow = new PolicyRule(
            "sys.default.query_allow",
            false,
            "agent.query_content",
            "allow",
            50
        );
        mPolicyRules.add(queryAllow);
        
        Slog.i(TAG, "Default policies loaded: " + mPolicyRules.size() + " rules");
    }
    
    // ==================== IPolicyEngine 接口实现 ====================
    
    @Override
    public int evaluateIntent(IntentEvaluationRequest request, PolicyDecision decision) {
        mTotalEvaluations.incrementAndGet();
        
        try {
            // 参数校验
            if (request == null || request.agentId == null || request.intentId == null) {
                Slog.w(TAG, "Invalid intent evaluation request");
                return STATUS_INVALID_REQ;
            }
            
            // 检查请求的能力是否都已注册
            if (request.requestedCapabilities != null) {
                for (String cap : request.requestedCapabilities) {
                    if (!mRegisteredCapabilities.contains(cap)) {
                        Slog.w(TAG, "Unknown capability: " + cap);
                        decision.decision = DEC_DENY;
                        decision.reasons = new String[]{"capability_not_registered: " + cap};
                        mDenyCount.incrementAndGet();
                        return STATUS_UNKNOWN_CAP;
                    }
                }
            }
            
            // 意图级预检：评估整体意图可行性
            // v1 简化实现：如果所有能力都可授权（或需确认），则意图可行
            boolean needsConfirmation = false;
            List<String> reasons = new ArrayList<>();
            
            if (request.requestedCapabilities != null) {
                for (String cap : request.requestedCapabilities) {
                    PolicyRule matchedRule = findMatchingRule(cap, null);
                    if (matchedRule != null) {
                        if ("deny".equals(matchedRule.effect)) {
                            decision.decision = DEC_DENY;
                            decision.reasons = new String[]{"policy_deny: " + matchedRule.id};
                            mDenyCount.incrementAndGet();
                            return STATUS_POLICY_DENY;
                        } else if ("require_confirmation".equals(matchedRule.effect)) {
                            needsConfirmation = true;
                            reasons.add("needs_confirmation: " + cap);
                        }
                    }
                }
            }
            
            // 构造决策结果
            if (needsConfirmation) {
                decision.decision = DEC_REQUIRE_CONFIRMATION;
                decision.reasons = reasons.toArray(new String[0]);
                mConfirmationCount.incrementAndGet();
            } else {
                decision.decision = DEC_GRANT;
                decision.reasons = new String[]{"intent_feasible"};
                mGrantCount.incrementAndGet();
            }
            
            decision.ruleVersion = mPolicyVersion;
            
            return STATUS_OK;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error evaluating intent", e);
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    @Override
    public int requestCapability(CapabilityRequest request, PolicyDecision decision) {
        mTotalEvaluations.incrementAndGet();
        
        try {
            // 参数校验
            if (request == null || request.agentId == null || request.intentId == null ||
                request.capability == null) {
                Slog.w(TAG, "Invalid capability request");
                return STATUS_INVALID_REQ;
            }
            
            // 检查能力是否已注册
            if (!mRegisteredCapabilities.contains(request.capability)) {
                Slog.w(TAG, "Unknown capability: " + request.capability);
                decision.decision = DEC_DENY;
                decision.reasons = new String[]{"capability_not_registered"};
                mDenyCount.incrementAndGet();
                return STATUS_UNKNOWN_CAP;
            }
            
            // 检查用户授权记忆
            String authzKey = request.agentId + ":" + request.capability;
            UserAuthzState userAuthz = mUserAuthorizations.get(authzKey);
            if (userAuthz != null) {
                // 检查授权是否仍有效
                if (userAuthz.scope == USER_ALLOW_TASK &&
                    request.intentId.equals(userAuthz.scopeIntentId)) {
                    // Task 级授权匹配，直接签发令牌
                    CapabilityToken token = issueToken(request);
                    decision.decision = DEC_GRANT;
                    decision.token = token;
                    decision.reasons = new String[]{"user_authz_task"};
                    decision.ruleVersion = mPolicyVersion;
                    mGrantCount.incrementAndGet();
                    return STATUS_OK;
                }
            }
            
            // 匹配策略规则
            PolicyRule matchedRule = findMatchingRule(request.capability, request.actionId);
            
            if (matchedRule == null) {
                // 未知即拒
                decision.decision = DEC_DENY;
                decision.reasons = new String[]{"no_matching_rule"};
                mDenyCount.incrementAndGet();
                return STATUS_POLICY_DENY;
            }
            
            // 更新规则统计
            matchedRule.hitCount++;
            matchedRule.lastHitNanos = System.nanoTime();
            
            // Taint 检查
            if (!matchedRule.argSourceRequirements.isEmpty()) {
                List<String> taintViolations = checkTaintRequirements(
                    matchedRule, request.argTaints);
                if (!taintViolations.isEmpty()) {
                    decision.decision = DEC_REQUIRE_MITIGATION;
                    decision.requiredMitigations = taintViolations.toArray(new String[0]);
                    decision.reasons = new String[]{"taint_violation: " + matchedRule.id};
                    decision.ruleVersion = mPolicyVersion;
                    mMitigationCount.incrementAndGet();
                    return STATUS_OK;
                }
            }
            
            // 根据规则效果执行裁决
            if ("allow".equals(matchedRule.effect)) {
                CapabilityToken token = issueToken(request);
                decision.decision = DEC_GRANT;
                decision.token = token;
                decision.reasons = new String[]{"policy_allow: " + matchedRule.id};
                decision.ruleVersion = mPolicyVersion;
                mGrantCount.incrementAndGet();
                return STATUS_OK;
                
            } else if ("deny".equals(matchedRule.effect)) {
                decision.decision = DEC_DENY;
                decision.reasons = new String[]{"policy_deny: " + matchedRule.id};
                decision.ruleVersion = mPolicyVersion;
                mDenyCount.incrementAndGet();
                return STATUS_POLICY_DENY;
                
            } else if ("require_confirmation".equals(matchedRule.effect)) {
                // 挂起并等待用户确认（confirmId = rawId.HMAC，防伪造/防重放）
                String rawId = UUID.randomUUID().toString();
                PendingConfirmation pending = new PendingConfirmation(rawId, request);
                String confirmId = issueConfirmId(pending);
                mPendingConfirmations.put(rawId, pending);
                
                decision.decision = DEC_REQUIRE_CONFIRMATION;
                decision.confirmId = confirmId;
                decision.reasons = new String[]{"needs_user_confirmation: " + matchedRule.id};
                decision.ruleVersion = mPolicyVersion;
                mConfirmationCount.incrementAndGet();
                return STATUS_CONFIRM_PENDING;
                
            } else if ("require_mitigation".equals(matchedRule.effect)) {
                decision.decision = DEC_REQUIRE_MITIGATION;
                decision.requiredMitigations = new String[]{"review_action_parameters"};
                decision.reasons = new String[]{"mitigation_required: " + matchedRule.id};
                decision.ruleVersion = mPolicyVersion;
                mMitigationCount.incrementAndGet();
                return STATUS_OK;
            }
            
            // 未知效果，回退 deny
            decision.decision = DEC_DENY;
            decision.reasons = new String[]{"unknown_effect"};
            mDenyCount.incrementAndGet();
            return STATUS_POLICY_DENY;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error requesting capability", e);
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    @Override
    /**
     * 复验 confirmId 的真实性：HMAC 正确、未过期、仍在挂起表中。**不消费**该确认。
     *
     * 供执行中介（ActionExecutionService）与确认宿主（ConfirmationHostService）在
     * 回灌/展示决策前做独立复验 —— WBS 1.5：此前两侧都只判"字符串非空"，
     * 等于让 Agent 可以自批自己的高风险动作。
     *
     * 非 AIDL 方法：仅 system_server 进程内调用，不经 Binder。
     */
    public boolean isGenuinePendingConfirmation(String confirmId) {
        if (confirmId == null) {
            return false;
        }
        int dot = confirmId.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        PendingConfirmation pending = mPendingConfirmations.get(confirmId.substring(0, dot));
        if (pending == null) {
            return false;
        }
        if (System.nanoTime() - pending.createdAtNanos > CONFIRM_ID_TTL_NANOS) {
            return false;
        }
        return verifyConfirmId(confirmId, pending);
    }

    public int reportUserDecision(String confirmId, int userDecision, CapabilityToken token) {
        try {
            if (confirmId == null) {
                return STATUS_INVALID_REQ;
            }
            
            // 解析 rawId 并做 HMAC 校验（伪造/篡改 confirmId 一律拒绝）
            int dot = confirmId.indexOf('.');
            if (dot <= 0) {
                Slog.w(TAG, "Malformed confirmId (missing HMAC): " + confirmId);
                return STATUS_INVALID_REQ;
            }
            String rawId = confirmId.substring(0, dot);
            PendingConfirmation pending = mPendingConfirmations.get(rawId);
            if (pending == null) {
                Slog.w(TAG, "Unknown confirmId: " + rawId);
                return STATUS_INVALID_REQ;
            }
            
            // 恒定时间 HMAC 比较（E1.1：Agent 伪造 confirmId 必须被拒绝）
            if (!verifyConfirmId(confirmId, pending)) {
                Slog.w(TAG, "Forged confirmId rejected: " + rawId);
                mPendingConfirmations.remove(rawId);
                return STATUS_INVALID_REQ;
            }
            
            // 确认超时（E1.1：超时后动作不得执行）
            if (System.nanoTime() - pending.createdAtNanos > CONFIRM_ID_TTL_NANOS) {
                Slog.w(TAG, "Expired confirmId: " + rawId);
                mPendingConfirmations.remove(rawId);
                return STATUS_INVALID_REQ;
            }
            
            // 校验通过，取走挂起请求（一次性）
            mPendingConfirmations.remove(rawId);
            
            if (userDecision == USER_ALLOW_ONCE || userDecision == USER_ALLOW_TASK) {
                // 用户允许，签发令牌（A4.2：真实签名 + 短时 TTL）
                CapabilityToken issued = issueToken(pending.request);
                
                // 补签令牌回灌到 out 出参（A3.2：执行中介确认路径据此继续执行）
                if (token != null && issued != null) {
                    token.tokenId = issued.tokenId;
                    token.agentId = issued.agentId;
                    token.capability = issued.capability;
                    token.scopeIntentId = issued.scopeIntentId;
                    token.issuedAtRealtimeNanos = issued.issuedAtRealtimeNanos;
                    token.expiresAtRealtimeNanos = issued.expiresAtRealtimeNanos;
                    token.issuerSignature = issued.issuerSignature;
                    token.revoked = issued.revoked;
                    token.policyVersion = issued.policyVersion;
                }
                
                // 记忆化授权
                if (userDecision == USER_ALLOW_TASK) {
                    String authzKey = pending.request.agentId + ":" + pending.request.capability;
                    UserAuthzState authz = new UserAuthzState(
                        pending.request.agentId,
                        pending.request.capability,
                        USER_ALLOW_TASK,
                        pending.request.intentId
                    );
                    mUserAuthorizations.put(authzKey, authz);
                    Slog.d(TAG, "User authorization memorized: " + authzKey);
                }
                
                Slog.i(TAG, "User decision: ALLOW, confirmId=" + rawId);
                return STATUS_OK;
                
            } else if (userDecision == USER_DENY) {
                // 用户拒绝：不签发令牌，token 保持为空对象
                Slog.i(TAG, "User decision: DENY, confirmId=" + rawId);
                return STATUS_OK;
            }
            
            return STATUS_INVALID_REQ;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error reporting user decision", e);
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    @Override
    public boolean validateToken(CapabilityToken token, String scopeIntentId, String capability) {
        // A4.2：委托令牌服务端全查（签名/scope/能力/TTL/撤销），fail-secure
        return mTokenService.validateToken(token, scopeIntentId, capability);
    }
    
    @Override
    public int revokeTokens(String agentId, String intentId) {
        try {
            // A4.2：委托令牌服务端（撤销列表 + 活跃索引 + 缓存失效）
            int revokedCount;
            if (intentId == null) {
                revokedCount = mTokenService.revokeTokensByAgent(agentId, "agent_terminated");
            } else {
                revokedCount = mTokenService.revokeTokensByIntent(
                    agentId, intentId, "intent_completed");
            }
            
            // 清理用户授权记忆（如果撤销全部）
            if (intentId == null) {
                mUserAuthorizations.entrySet().removeIf(
                    entry -> entry.getValue().agentId.equals(agentId));
            }
            
            // 通知监听器
            notifyTokensRevoked(agentId, intentId);
            
            Slog.i(TAG, "Tokens revoked: agentId=" + agentId + ", intentId=" + intentId +
                ", count=" + revokedCount);
            
            return STATUS_OK;
            
        } catch (Exception e) {
            Slog.e(TAG, "Error revoking tokens", e);
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    @Override
    public PolicyRuleView[] listApplicableRules(String agentId) {
        try {
            List<PolicyRuleView> views = new ArrayList<>();
            
            for (PolicyRule rule : mPolicyRules) {
                PolicyRuleView view = new PolicyRuleView();
                view.ruleId = rule.id;
                view.baseline = rule.baseline;
                view.capability = rule.capability;
                view.effect = rule.effect;
                view.hitCount = rule.hitCount;
                view.lastHitAtRealtimeNanos = rule.lastHitNanos;
                views.add(view);
            }
            
            return views.toArray(new PolicyRuleView[0]);
            
        } catch (Exception e) {
            Slog.e(TAG, "Error listing applicable rules", e);
            return new PolicyRuleView[0];
        }
    }
    
    @Override
    public int reloadPolicySet(String signedPolicyBundle, String[] bundleVersion) {
        // A7.1 装载口子②：验签 → 源标记 → 双缓冲原子换入 → 失败保留旧档
        if (signedPolicyBundle == null) {
            return STATUS_INVALID_REQ;
        }
        int status = doReload(signedPolicyBundle, null);
        if (status == STATUS_OK && bundleVersion != null && bundleVersion.length > 0) {
            bundleVersion[0] = mPolicyVersion;
        }
        return status;
    }
    
    /**
     * 活跃路由域规则视图（治理/D6.2 回显，只查询）。
     * domain=null 时返回全部已装载域。
     */
    @Override
    public PolicyRuleView[] listActiveRules(String domain) {
        List<PolicyRuleView> views = new ArrayList<>();
        List<String> domains = (domain == null || domain.isEmpty())
                ? mEvaluator.getActiveDomains()
                : java.util.Collections.singletonList(domain);
        for (String d : domains) {
            PolicyRuleView[] arr = mEvaluator.getActiveRuleViews(d);
            if (arr != null) {
                Collections.addAll(views, arr);
            }
        }
        return views.toArray(new PolicyRuleView[0]);
    }
    
    // ==================== 路由域决策导出（A7.1，进程内） ====================
    
    /**
     * 通知域路由求值（进程内消费方：SystemUI 通知栈/NotificationRoutingGate）。
     * facts 键对齐 NotificationDescriptor + value_domain context_fields：
     *  event / senderDomain / senderFingerprint / senderTrust / category /
     *  classifierConfidence / userPinnedP0 / context.dndActive
     */
    public com.genton.policy.PolicyDecision evaluateNotification(Map<String, Object> facts) {
        return evaluateDomainRoute("notification", facts);
    }
    
    /**
     * 来电域路由求值（进程内消费方：CallGate）。
     * facts 键：call / callerClass / callerIs / numberPrefix / spamScore /
     *  callRepeatedInWindowMs / callerHasRecentOutgoingWithinDays / dailyAiHandleUsed /
     *  gateMode / context.dndActive
     */
    public com.genton.policy.PolicyDecision evaluateCall(Map<String, Object> facts) {
        return evaluateDomainRoute("call_gate", facts);
    }
    
    /** 域路由决策组装：命中规则 → route/routeParams/版本；未命中 → fail-safe 默认路由。 */
    private com.genton.policy.PolicyDecision evaluateDomainRoute(String domain,
                                                                   Map<String, Object> facts) {
        com.genton.policy.PolicyDecision decision = new com.genton.policy.PolicyDecision();
        decision.domain = domain;
        decision.decision = DEC_GRANT;   // 路由域决策：命中即放行至对应 route（deny-protect 由 routeFor 吸收）
        
        PolicyRuleEvaluator.DomainMatch m = mEvaluator.matchDomain(domain, facts);
        if (m.rule != null) {
            decision.route = m.rule.routeConstant();
            decision.routeParams = m.rule.routeParamsJson();
            String baselineMark = m.rule.baseline ? " (baseline)" : "";
            decision.reasons = new String[]{
                    "matched:" + m.rule.id + baselineMark
                            + (m.rule.reasonText() != null ? " - " + m.rule.reasonText() : "")};
        } else {
            decision.route = CompiledPolicyRule.defaultRouteFor(domain);
            decision.reasons = new String[]{m.domainActive
                    ? "no_match_default_route"
                    : "domain_not_loaded_default_route"};
        }
        decision.ruleVersion = mPolicyVersion;
        return decision;
    }
    
    /**
     * 域命中规则直达（进程内，供 A7.3 执行器取 auditLevel/命中详情构建本地决策）。
     */
    public PolicyRuleEvaluator.DomainMatch resolveDomainRule(String domain,
                                                             Map<String, Object> facts) {
        return mEvaluator.matchDomain(domain, facts);
    }
    
    /**
     * 最近一次装载错误详情（治理/诊断）。
     */
    public Map<String, String> getLoadErrors() {
        return new HashMap<>(mLastLoadError);
    }
    
    @Override
    public int registerListener(IPolicyListener listener) {
        if (listener != null) {
            mListeners.register(listener);
            return STATUS_OK;
        }
        return STATUS_INVALID_REQ;
    }
    
    @Override
    public int unregisterListener(IPolicyListener listener) {
        if (listener != null) {
            mListeners.unregister(listener);
            return STATUS_OK;
        }
        return STATUS_INVALID_REQ;
    }
    
    @Override
    public PolicyStats getStats() {
        PolicyStats stats = new PolicyStats();
        stats.totalEvaluations = mTotalEvaluations.get();
        stats.grantCount = mGrantCount.get();
        stats.denyCount = mDenyCount.get();
        stats.confirmationCount = mConfirmationCount.get();
        stats.mitigationCount = mMitigationCount.get();
        stats.pendingConfirmations = mPendingConfirmations.size();
        stats.activeTokens = mTokenService.getStats().activeTokens;
        stats.policyVersion = mPolicyVersion;
        stats.policyLoadedAtNanos = mPolicyLoadedAtNanos;
        return stats;
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 查找匹配的策略规则
     * 
     * 优先级：baseline > priority 高 > priority 低
     * 效果优先级：deny > require_confirmation > allow
     */
    private PolicyRule findMatchingRule(String capability, String actionId) {
        PolicyRule matchedRule = null;
        
        // 按优先级排序（baseline 优先，然后按 priority 降序）
        for (PolicyRule rule : mPolicyRules) {
            // 能力匹配
            if ("*".equals(rule.capability) || capability.equals(rule.capability)) {
                // actionId 匹配（如果规则指定了）
                if (rule.actionId == null || rule.actionId.equals(actionId)) {
                    // 第一次匹配或优先级更高
                    if (matchedRule == null ||
                        rule.baseline && !matchedRule.baseline ||
                        rule.priority > matchedRule.priority) {
                        matchedRule = rule;
                    }
                    
                    // Baseline deny 立即返回（最高优先级）
                    if (rule.baseline && "deny".equals(rule.effect)) {
                        return rule;
                    }
                }
            }
        }
        
        return matchedRule;
    }
    
    /**
     * 检查 Taint 约束
     * 
     * @return 违反约束的参数列表
     */
    private List<String> checkTaintRequirements(PolicyRule rule, TaintMapEntry[] argTaints) {
        List<String> violations = new ArrayList<>();
        
        if (rule.argSourceRequirements.isEmpty()) {
            return violations;
        }
        
        // 构建 argKey → trust 映射
        Map<String, Integer> taintMap = new HashMap<>();
        if (argTaints != null) {
            for (TaintMapEntry entry : argTaints) {
                taintMap.put(entry.argKey, entry.trust);
            }
        }
        
        // 检查每个约束
        for (Map.Entry<String, Integer> requirement : rule.argSourceRequirements.entrySet()) {
            String argKey = requirement.getKey();
            int requiredTrust = requirement.getValue();
            
            Integer actualTrust = taintMap.get(argKey);
            if (actualTrust == null) {
                // 参数未提供，视为违反
                violations.add(argKey + ": missing_taint_info");
                continue;
            }
            
            // 检查信任等级（数值越小越信任）
            if (actualTrust > requiredTrust) {
                violations.add(argKey + ": requires_trust_" + getTrustName(requiredTrust) +
                    ", actual_" + getTrustName(actualTrust));
            }
        }
        
        return violations;
    }
    
    /**
     * 签发能力令牌（A4.2：委托令牌服务端；scope 锚定 intentId + 短时 TTL + 策略版本绑定 + 真实签名）
     */
    private CapabilityToken issueToken(CapabilityRequest request) {
        CapabilityToken token = mTokenService.issueToken(
            request.agentId, request.capability, request.intentId, mPolicyVersion);
        Slog.d(TAG, "Token issued: tokenId=" + token.tokenId + ", capability=" + token.capability);
        return token;
    }
    
    // ==================== A4.2 confirmId HMAC（E1.1 红线） ====================
    
    /** confirmId = rawId "." base64url(HMAC-SHA256(confirm|rawId|agentId|capability|intentId)) */
    private String issueConfirmId(PendingConfirmation pending) {
        byte[] mac = hmacSha256(confirmPayload(pending));
        return pending.confirmId + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }
    
    /** 恒定时间校验 confirmId（防伪造/篡改/重放） */
    private boolean verifyConfirmId(String confirmId, PendingConfirmation pending) {
        int dot = confirmId.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String raw = confirmId.substring(0, dot);
        if (!raw.equals(pending.confirmId)) {
            return false;
        }
        byte[] expected = hmacSha256(confirmPayload(pending));
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(confirmId.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }
    
    /** HMAC 绑定载荷：把 confirmId 锚定到具体请求（agent/能力/意图），防跨请求复用 */
    private String confirmPayload(PendingConfirmation pending) {
        return "confirm|" + pending.confirmId + "|" + pending.request.agentId
                + "|" + pending.request.capability + "|" + pending.request.intentId;
    }
    
    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mConfirmKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("confirmId HMAC unavailable", e);
        }
    }
    
    /**
     * 通知监听器：令牌已撤销
     */
    private void notifyTokensRevoked(String agentId, String intentId) {
        int count = mListeners.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mListeners.getBroadcastItem(i).onTokensRevoked(agentId, intentId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify listener", e);
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
    }
    
    /**
     * 通知监听器：策略包已更新
     */
    private void notifyPolicyBundleUpdated(String bundleVersion) {
        int count = mListeners.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mListeners.getBroadcastItem(i).onPolicyBundleUpdated(bundleVersion);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify listener", e);
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
    }
    
    /**
     * 获取信任等级名称
     */
    private String getTrustName(int trust) {
        switch (trust) {
            case TRUST_SYSTEM: return "SYSTEM";
            case TRUST_USER: return "USER";
            case TRUST_APP_CONTENT: return "APP_CONTENT";
            case TRUST_WEB_CONTENT: return "WEB_CONTENT";
            default: return "UNKNOWN";
        }
    }
}
