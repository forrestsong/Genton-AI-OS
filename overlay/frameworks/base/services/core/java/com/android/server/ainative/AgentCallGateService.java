package com.android.server.ainative;

import android.content.Context;
import android.util.Slog;
import com.ainative.call.IAgentCallGateService;
import com.ainative.call.IAgentCallGateListener;
import com.ainative.call.CallDescriptor;
import com.ainative.call.CallVerdict;
import com.ainative.call.WhitelistEntry;
import com.ainative.call.GateMode;
import com.ainative.call.CallSessionHandle;
import com.ainative.call.TranscriptDelta;
import com.ainative.call.CallSummary;
import com.ainative.policy.PolicyDecision;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CallGate 通话门控服务（A10.1）
 * 
 * 职责：
 * - 来电判定：白名单检查 + 策略引擎路由（≤50ms）
 * - 白名单管理：联系人/号段/紧急号码（系统保底）
 * - 接管模式管理：L1/L2/L3 授权状态
 * - AI 接管会话：音频桥接 + 本地 ASR/TTS + 摘要
 * 
 * 红线：
 * - 紧急号码/系统号码永不可拦（策略不可覆盖）
 * - 音频本地处理，不落记忆/云，录音默认不存
 * - L1/L2 模式默认关闭，需用户授权，每通审计
 * - 判定必须 ≤50ms，超时回退原生响铃（fail-safe）
 * 
 * 配套组件：
 * - CallGateRoutingExecutor：消费策略引擎决策，转换为 CallVerdict
 * - PolicyEngineService：call_gate 域策略求值
 * - 本地 ASR/TTS：音频处理（TEE 边界，不落云端）
 * 
 * @see IAgentCallGateService
 * @see CallGateRoutingExecutor
 * @see PolicyEngineService
 */
public class AgentCallGateService extends IAgentCallGateService.Stub {
    
    private static final String TAG = "AgentCallGateService";
    
    // ==================== 性能常量 ====================
    private static final long CLASSIFY_TIMEOUT_MS = 50;  // 判定超时（fail-safe）
    
    // ==================== 紧急号码（系统保底，不可删除）====================
    private static final String[] EMERGENCY_NUMBERS = {
        "+86110",  // 警察
        "+86119",  // 消防
        "+86120",  // 急救
        "+86122",  // 交通事故
        "110", "119", "120", "122"  // 短号码格式
    };
    
    private final Context mContext;
    private final PolicyEngineService mPolicyEngine;
    private final CallGateRoutingExecutor mRoutingExecutor;
    
    // ==================== 白名单存储 ====================
    private final Map<String, WhitelistEntry> mWhitelist = new ConcurrentHashMap<>();
    
    // ==================== 接管模式配置 ====================
    private GateMode mCurrentGateMode;
    
    // ==================== 活跃会话 ====================
    private final Map<String, CallSession> mActiveSessions = new ConcurrentHashMap<>();
    
    /**
     * 构造函数
     */
    public AgentCallGateService(Context context, PolicyEngineService policyEngine) {
        mContext = context;
        mPolicyEngine = policyEngine;
        mRoutingExecutor = new CallGateRoutingExecutor();
        
        // 初始化白名单（加载紧急号码）
        initializeWhitelist();
        
        // 初始化接管模式（默认关闭）
        mCurrentGateMode = createDefaultGateMode();
        
        Slog.i(TAG, "AgentCallGateService initialized");
    }
    
    // ==================== 来电判定（≤50ms）====================
    
    @Override
    public int classifyIncoming(CallDescriptor descriptor, CallVerdict verdict) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 阶段 1：baseline 检查（紧急号码/系统号码强制原生响铃）
            if (descriptor.isEmergency || descriptor.isSystemNumber) {
                verdict.route = IAgentCallGateService.ROUTE_RING_NATIVE;
                verdict.handleMode = 0;
                verdict.reason = descriptor.isEmergency ? "紧急号码" : "系统号码";
                verdict.baselineGuard = true;
                verdict.ruleId = "sys.callgate.baseline-guard";
                verdict.elapsedMs = System.currentTimeMillis() - startTime;
                
                Slog.i(TAG, "Baseline guard: " + descriptor.numberE164 + " → RING_NATIVE");
                return IAgentCallGateService.STATUS_OK;
            }
            
            // 阶段 2：白名单检查（联系人/用户添加）
            if (isWhitelisted(descriptor.numberE164)) {
                verdict.route = IAgentCallGateService.ROUTE_RING_NATIVE;
                verdict.handleMode = 0;
                verdict.reason = "白名单";
                verdict.baselineGuard = false;
                verdict.ruleId = "user.callgate.whitelist";
                verdict.elapsedMs = System.currentTimeMillis() - startTime;
                
                Slog.i(TAG, "Whitelist match: " + descriptor.numberE164 + " → RING_NATIVE");
                return IAgentCallGateService.STATUS_OK;
            }
            
            // 阶段 3：策略引擎路由（call_gate 域）
            Map<String, Object> facts = buildCallFacts(descriptor);
            PolicyDecision policyDecision = mPolicyEngine.evaluateCall(facts);
            
            // 阶段 4：路由执行器转换（PolicyDecision → CallVerdict）
            CallGateRoutingExecutor.RoutingResult routingResult = 
                mRoutingExecutor.routeIncomingCall(descriptor, policyDecision);
            
            // 检查超时（性能监控）
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > CLASSIFY_TIMEOUT_MS) {
                Slog.w(TAG, "classifyIncoming exceeded timeout: " + elapsed + "ms (target: " + 
                       CLASSIFY_TIMEOUT_MS + "ms) → fallback to RING_NATIVE");
                
                // 超时回退原生响铃（fail-safe）
                verdict.route = IAgentCallGateService.ROUTE_RING_NATIVE;
                verdict.handleMode = 0;
                verdict.reason = "判定超时（fail-safe）";
                verdict.baselineGuard = false;
                verdict.ruleId = "sys.callgate.timeout-fallback";
                verdict.elapsedMs = elapsed;
                
                return IAgentCallGateService.STATUS_OK;
            }
            
            // 阶段 5：模式授权检查（L1/L2 需授权）
            if (routingResult.route == IAgentCallGateService.ROUTE_AGENT_HANDLE) {
                if (!mCurrentGateMode.authorized || mCurrentGateMode.mode == 0) {
                    Slog.w(TAG, "AI handle mode not authorized → fallback to RING_NATIVE");
                    
                    verdict.route = IAgentCallGateService.ROUTE_RING_NATIVE;
                    verdict.handleMode = 0;
                    verdict.reason = "接管模式未授权";
                    verdict.baselineGuard = false;
                    verdict.ruleId = "sys.callgate.not-authorized";
                    verdict.elapsedMs = elapsed;
                    
                    return IAgentCallGateService.STATUS_NOT_ENABLED;
                }
                
                // 检查日配额
                if (mCurrentGateMode.dailyHandleCap > 0 && 
                    mCurrentGateMode.dailyHandleUsed >= mCurrentGateMode.dailyHandleCap) {
                    Slog.w(TAG, "Daily AI handle cap reached → fallback to RECORD_ONLY");
                    
                    verdict.route = IAgentCallGateService.ROUTE_RECORD_ONLY;
                    verdict.handleMode = IAgentCallGateService.HANDLE_MODE_L3_RECORD_ONLY;
                    verdict.reason = "日配额已达上限";
                    verdict.baselineGuard = false;
                    verdict.ruleId = "sys.callgate.daily-cap";
                    verdict.elapsedMs = elapsed;
                    
                    return IAgentCallGateService.STATUS_OK;
                }
            }
            
            // 阶段 6：填充 CallVerdict
            verdict.route = routingResult.route;
            verdict.handleMode = routingResult.handleMode;
            verdict.ruleId = routingResult.ruleId;
            verdict.policyRef = routingResult.policyRef;
            verdict.reason = routingResult.reason;
            verdict.baselineGuard = false;
            verdict.greetingTextRef = mCurrentGateMode.greetingTextRef;
            verdict.elapsedMs = elapsed;
            
            Slog.i(TAG, "classifyIncoming: " + descriptor.numberE164 + 
                   " → route=" + verdict.route + ", handleMode=" + verdict.handleMode + 
                   ", elapsed=" + elapsed + "ms");
            
            return IAgentCallGateService.STATUS_OK;
            
        } catch (Exception e) {
            Slog.e(TAG, "classifyIncoming failed", e);
            
            // 异常回退原生响铃（fail-safe）
            verdict.route = IAgentCallGateService.ROUTE_RING_NATIVE;
            verdict.handleMode = 0;
            verdict.reason = "判定异常（fail-safe）";
            verdict.baselineGuard = false;
            verdict.ruleId = "sys.callgate.error-fallback";
            verdict.elapsedMs = System.currentTimeMillis() - startTime;
            
            return IAgentCallGateService.STATUS_INTERNAL_ERROR;
        }
    }
    
    // ==================== 白名单管理 ====================
    
    @Override
    public boolean isWhitelisted(String numberE164) {
        if (numberE164 == null || numberE164.isEmpty()) {
            return false;
        }
        
        // 精确匹配
        if (mWhitelist.containsKey(numberE164)) {
            return true;
        }
        
        // 号段匹配（如 +86138*）
        for (WhitelistEntry entry : mWhitelist.values()) {
            if (entry.numberPattern.endsWith("*")) {
                String prefix = entry.numberPattern.substring(0, entry.numberPattern.length() - 1);
                if (numberE164.startsWith(prefix)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    @Override
    public int addWhitelistEntry(WhitelistEntry entry) {
        if (entry == null || entry.numberPattern == null || entry.numberPattern.isEmpty()) {
            Slog.w(TAG, "Invalid whitelist entry");
            return IAgentCallGateService.STATUS_DENIED;
        }
        
        // 紧急号码不可手动添加（已由系统初始化）
        if (isEmergencyNumber(entry.numberPattern)) {
            Slog.w(TAG, "Emergency number cannot be manually added: " + entry.numberPattern);
            return IAgentCallGateService.STATUS_DENIED;
        }
        
        mWhitelist.put(entry.numberPattern, entry);
        Slog.i(TAG, "Whitelist entry added: " + entry.numberPattern + " (source=" + entry.sourceType + ")");
        
        return IAgentCallGateService.STATUS_OK;
    }
    
    @Override
    public int removeWhitelistEntry(String numberE164) {
        WhitelistEntry entry = mWhitelist.get(numberE164);
        if (entry == null) {
            Slog.w(TAG, "Whitelist entry not found: " + numberE164);
            return IAgentCallGateService.STATUS_OK;  // 已不存在，视为成功
        }
        
        // 系统保护条目不可删除
        if (entry.systemProtected) {
            Slog.w(TAG, "System-protected entry cannot be removed: " + numberE164);
            return IAgentCallGateService.STATUS_DENIED;
        }
        
        mWhitelist.remove(numberE164);
        Slog.i(TAG, "Whitelist entry removed: " + numberE164);
        
        return IAgentCallGateService.STATUS_OK;
    }
    
    @Override
    public List<WhitelistEntry> listWhitelist() {
        return new ArrayList<>(mWhitelist.values());
    }
    
    // ==================== 接管模式管理 ====================
    
    @Override
    public GateMode getGateMode() {
        return mCurrentGateMode;
    }
    
    @Override
    public int setGateMode(GateMode mode) {
        if (mode == null) {
            Slog.w(TAG, "Invalid gate mode");
            return IAgentCallGateService.STATUS_DENIED;
        }
        
        // TODO: 验证用户授权（需集成权限系统）
        
        mCurrentGateMode = mode;
        Slog.i(TAG, "Gate mode updated: mode=" + mode.mode + ", callerScope=" + mode.callerScope + 
               ", authorized=" + mode.authorized);
        
        return IAgentCallGateService.STATUS_OK;
    }
    
    // ==================== AI 接管会话 ====================
    
    @Override
    public int startHandleSession(CallDescriptor descriptor, CallVerdict verdict, 
                                   IAgentCallGateListener listener, CallSessionHandle session) {
        // 检查是否已有活跃会话
        if (!mActiveSessions.isEmpty()) {
            Slog.w(TAG, "Cannot start handle session: another session is active");
            return IAgentCallGateService.STATUS_SESSION_BUSY;
        }
        
        // 创建会话
        String sessionId = "callsession_" + System.currentTimeMillis();
        CallSession callSession = new CallSession(sessionId, descriptor, verdict, listener);
        mActiveSessions.put(sessionId, callSession);
        
        // 填充会话句柄
        session.sessionId = sessionId;
        session.numberHash = descriptor.numberHash;
        session.startedAtRealtimeNanos = System.nanoTime();
        session.handleMode = verdict.handleMode;
        
        // 更新日配额计数
        if (verdict.route == IAgentCallGateService.ROUTE_AGENT_HANDLE) {
            mCurrentGateMode.dailyHandleUsed++;
        }
        
        // 通知监听器：判定结果
        try {
            listener.onVerdict(descriptor, verdict);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to notify verdict", e);
        }
        
        // TODO: 启动音频桥接 + 本地 ASR/TTS
        // 这里需要集成 TEE 边界内的 ASR/TTS 模块（A10.1 后续子任务）
        
        Slog.i(TAG, "Handle session started: " + sessionId + " (handleMode=" + verdict.handleMode + ")");
        
        return IAgentCallGateService.STATUS_OK;
    }
    
    @Override
    public int endHandleSession(CallSessionHandle session) {
        CallSession callSession = mActiveSessions.remove(session.sessionId);
        if (callSession == null) {
            Slog.w(TAG, "Session not found: " + session.sessionId);
            return IAgentCallGateService.STATUS_OK;  // 已不存在，视为成功
        }
        
        // TODO: 生成摘要并通知监听器
        // 这里需要集成本地摘要生成模块（A10.1 后续子任务）
        
        Slog.i(TAG, "Handle session ended: " + session.sessionId);
        
        return IAgentCallGateService.STATUS_OK;
    }
    
    @Override
    public int getSessionState(CallSessionHandle session) {
        CallSession callSession = mActiveSessions.get(session.sessionId);
        if (callSession == null) {
            return -1;  // not_found
        }
        
        return callSession.state;
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 初始化白名单（加载紧急号码）
     */
    private void initializeWhitelist() {
        for (String emergencyNumber : EMERGENCY_NUMBERS) {
            WhitelistEntry entry = new WhitelistEntry();
            entry.numberPattern = emergencyNumber;
            entry.sourceType = "emergency";
            entry.displayName = "紧急号码";
            entry.addedAtMs = System.currentTimeMillis();
            entry.systemProtected = true;
            
            mWhitelist.put(emergencyNumber, entry);
        }
        
        Slog.i(TAG, "Whitelist initialized with " + EMERGENCY_NUMBERS.length + " emergency numbers");
    }
    
    /**
     * 创建默认接管模式（关闭）
     */
    private GateMode createDefaultGateMode() {
        GateMode mode = new GateMode();
        mode.mode = 0;  // OFF
        mode.callerScope = "default";
        mode.greetingTextRef = "greeting.default";
        mode.authorized = false;
        mode.authorizedAtMs = 0;
        mode.dailyHandleCap = 3;  // 默认日配额 3 通
        mode.dailyHandleUsed = 0;
        
        return mode;
    }
    
    /**
     * 判断是否为紧急号码
     */
    private boolean isEmergencyNumber(String number) {
        for (String emergencyNumber : EMERGENCY_NUMBERS) {
            if (emergencyNumber.equals(number)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 构造策略引擎 facts（call_gate 域）
     */
    private Map<String, Object> buildCallFacts(CallDescriptor descriptor) {
        Map<String, Object> facts = new HashMap<>();
        
        facts.put("call", "incoming");
        facts.put("numberE164", descriptor.numberE164);
        facts.put("callerName", descriptor.callerName != null ? descriptor.callerName : "");
        facts.put("callerClass", isWhitelisted(descriptor.numberE164) ? "whitelist" : "unknown");
        facts.put("repeatedInWindowMs", descriptor.repeatedInWindowMs);
        facts.put("hasRecentOutgoingWithinDays", descriptor.hasRecentOutgoingWithinDays);
        facts.put("dailyAiHandleUsed", mCurrentGateMode.dailyHandleUsed);
        facts.put("gateMode", mCurrentGateMode.mode);
        
        // TODO: 添加反骚扰模型置信分数（需集成本地反骚扰模型）
        // facts.put("spamScore", getSpamScore(descriptor.numberE164));
        
        return facts;
    }
    
    // ==================== 内部类：会话状态 ====================
    
    private static class CallSession {
        final String sessionId;
        final CallDescriptor descriptor;
        final CallVerdict verdict;
        final IAgentCallGateListener listener;
        int state;  // 0=bridging, 1=greeting, 2=transcribing, 3=done, 4=error
        
        CallSession(String sessionId, CallDescriptor descriptor, CallVerdict verdict, 
                   IAgentCallGateListener listener) {
            this.sessionId = sessionId;
            this.descriptor = descriptor;
            this.verdict = verdict;
            this.listener = listener;
            this.state = 0;  // bridging
        }
    }
}
