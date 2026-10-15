package com.android.server.genton;

import android.util.Slog;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CallGate 路由执行器（A7.3 路由轴执行器接口）
 * 
 * 职责：
 * - 消费策略引擎 call_gate 域的路由决策（PolicyDecision）
 * - 将路由指令转换为 telecom 服务可执行的 CallVerdict
 * - 执行 baseline deny 检查（白名单/紧急号码强制原生响铃）
 * - 模式检查（L1/L2 需用户授权，未授权回退）
 * - 性能要求：来电判定 ≤50ms（策略引擎已计入）
 * - Fail-safe 原则：任何判定失败回退到 RING_NATIVE（安全可见）
 * 
 * 配套接口：
 * - D16 CallGate 通话门接口草案（AIDL 定义）
 * - D10 策略引擎接口草案（call_gate 域策略）
 * - PolicyDecision：路由决策数据结构
 * 
 * 路由域：
 * - RING_NATIVE：原生响铃（白名单/紧急号码/未授权兜底）
 * - AGENT_HANDLE：AI 接管（L1 静默留言 / L2 语音对话）
 * - RECORD_ONLY：仅记录 + 建议回拨（L3）
 * 
 * @see PolicyDecision
 */
public class CallGateRoutingExecutor {
    
    private static final String TAG = "CallGateRouting";
    
    // ==================== 路由常量（对齐 PolicyDecision）====================
    public static final int ROUTE_RING_NATIVE = PolicyDecision.ROUTE_RING_NATIVE;
    public static final int ROUTE_AGENT_HANDLE = PolicyDecision.ROUTE_AGENT_HANDLE;
    public static final int ROUTE_RECORD_ONLY = PolicyDecision.ROUTE_RECORD_ONLY;
    
    // ==================== 接管模式（L1/L2/L3）====================
    public static final int HANDLE_MODE_L1_SILENT_AI = 1;     // 静默接起+留言转文字
    public static final int HANDLE_MODE_L2_AUDIO_DIALOG = 2;  // AI 语音对话
    public static final int HANDLE_MODE_L3_RECORD_ONLY = 3;   // 仅记录（不接起）
    
    // ==================== 调用方信任等级 ====================
    public static final String TRUST_WHITELIST = "whitelist";      // 白名单（联系人/用户添加）
    public static final String TRUST_CONTACT = "contact";          // 通讯录
    public static final String TRUST_UNKNOWN = "unknown";          // 未知号码
    public static final String TRUST_SPAM_HIGH = "spam_model";     // 反骚扰模型高置信
    
    // ==================== 路由结果 ====================
    
    /**
     * 路由判定结果
     * 对应 D16 CallVerdict parcelable
     */
    public static class RoutingResult {
        /** 路由类型（ROUTE_*） */
        public int route;
        
        /** 接管模式（L1/L2/L3，仅 route=AGENT_HANDLE 时有效） */
        public int handleMode;
        
        /** 判定理由（脱敏，用户可见） */
        public String reason;
        
        /** 命中规则 ID（审计） */
        public String ruleId;
        
        /** 策略包版本引用 */
        public String policyRef;
        
        /** baseline 保底命中（紧急/系统号码） */
        public boolean baselineGuard;
        
        // ==================== L1/L2 接管参数 ====================
        
        /** 开场白模板引用（系统签发，防注入） */
        public String greetingTextRef;
        
        /** ASR 模型档位（small_local/medium_local） */
        public String asrModel;
        
        /** 摘要生成模型（local/cloud） */
        public String summaryModel;
        
        /** 最大留言时长（秒，超时自动挂断） */
        public int maxVoicemailSec;
        
        /** 是否生成建议回拨时机 */
        public boolean suggestCallback;
        
        // ==================== L3 仅记录参数 ====================
        
        /** 是否建议回拨 */
        public boolean recommendCallback;
        
        /** 回拨时机提示（如"工作日9-18点"） */
        public String callbackTimeHint;
        
        public RoutingResult() {
            this.route = ROUTE_RING_NATIVE;  // 默认安全回退
            this.handleMode = -1;
            this.baselineGuard = false;
            this.maxVoicemailSec = 60;  // 默认1分钟
            this.asrModel = "small_local";
            this.summaryModel = "local";
        }
    }
    
    /**
     * 来电描述符
     * 对应 D16 CallDescriptor parcelable
     */
    public static class CallDescriptor {
        /** 归一化号码（E164） */
        public String numberE164;
        
        /** 展示用掩码（如 138****8000） */
        public String numberMasked;
        
        /** 通讯录/实体解析出的脱敏名字 */
        public String nameHint;
        
        /** 调用方信任等级 */
        public String callerTrust;  // whitelist/contact/unknown/spam_model
        
        /** 原文指纹（审计关联） */
        public String rawNumberHash;
        
        /** 来电时间戳（System.nanoTime） */
        public long arrivalRealtimeNanos;
        
        /** 是否紧急号码（110/120/911等） */
        public boolean isEmergency;
        
        /** 是否系统号码（运营商/银行验证码等） */
        public boolean isSystemNumber;
    }
    
    // ==================== 状态管理 ====================
    
    /** 白名单缓存（numberE164 → true） */
    private final Map<String, Boolean> whitelistCache = new ConcurrentHashMap<>();
    
    /** 当前启用的接管模式（默认关闭） */
    private volatile int currentGateMode = 0;  // 0=off
    
    /** 接管模式授权状态（mode → authorized） */
    private final Map<Integer, Boolean> modeAuthorization = new ConcurrentHashMap<>();
    
    /** 统计：今日处理计数（route → count） */
    private final Map<Integer, Integer> dailyRouteCounts = new ConcurrentHashMap<>();
    
    public CallGateRoutingExecutor() {
        // 默认所有接管模式未授权
        modeAuthorization.put(HANDLE_MODE_L1_SILENT_AI, false);
        modeAuthorization.put(HANDLE_MODE_L2_AUDIO_DIALOG, false);
        modeAuthorization.put(HANDLE_MODE_L3_RECORD_ONLY, false);
        
        initDailyCounts();
    }
    
    // ==================== 核心路由方法 ====================
    
    /**
     * 来电路由判定（由 telecom 在响铃前调用，≤50ms）
     * 
     * Fail-safe 原则：任何判定失败/超时/异常 → RING_NATIVE（安全可见）
     * 
     * @param descriptor 来电描述符
     * @param policyDecision 策略引擎决策（可能为 null，表示无匹配规则）
     * @return 路由判定结果（绝不返回 null）
     */
    public RoutingResult routeIncomingCall(CallDescriptor descriptor, 
                                           PolicyDecision policyDecision) {
        RoutingResult result = new RoutingResult();
        
        try {
            // ==================== 阶段 1：baseline deny 检查 ====================
            
            // 1.1 紧急号码/系统号码：强制原生响铃（策略永不可覆盖）
            if (descriptor.isEmergency || descriptor.isSystemNumber) {
                result.route = ROUTE_RING_NATIVE;
                result.baselineGuard = true;
                result.reason = descriptor.isEmergency ? "紧急号码" : "系统号码";
                result.ruleId = "sys.callgate.baseline_guard";
                Slog.i(TAG, "Baseline guard hit: " + descriptor.numberMasked);
                return result;
            }
            
            // 1.2 白名单检查：联系人/用户添加的号码 → 原生响铃
            if (TRUST_WHITELIST.equals(descriptor.callerTrust) ||
                TRUST_CONTACT.equals(descriptor.callerTrust) ||
                isWhitelisted(descriptor.numberE164)) {
                result.route = ROUTE_RING_NATIVE;
                result.reason = "联系人/白名单";
                result.ruleId = "sys.callgate.whitelist";
                Slog.d(TAG, "Whitelist hit: " + descriptor.numberMasked);
                return result;
            }
            
            // ==================== 阶段 2：策略决策检查 ====================
            
            // 2.1 无策略决策 → 回退原生响铃（fail-safe）
            if (policyDecision == null || !policyDecision.hasRouteDecision()) {
                result.route = ROUTE_RING_NATIVE;
                result.reason = "无匹配规则";
                result.ruleId = "sys.callgate.no_match";
                Slog.d(TAG, "No policy match, fallback to native: " + descriptor.numberMasked);
                return result;
            }
            
            // 2.2 验证路由决策是否为 call_gate 域
            if (!policyDecision.isCallGateRoute()) {
                result.route = ROUTE_RING_NATIVE;
                result.reason = "非通话域决策";
                result.ruleId = "sys.callgate.wrong_domain";
                Slog.w(TAG, "Policy decision is not call_gate domain: " + policyDecision.domain);
                return result;
            }
            
            // ==================== 阶段 3：路由指令解析 ====================
            
            result.route = policyDecision.route;
            result.ruleId = policyDecision.ruleId;
            result.policyRef = policyDecision.policyVersion;
            
            switch (result.route) {
                case ROUTE_RING_NATIVE:
                    result.reason = "策略允许响铃";
                    break;
                    
                case ROUTE_AGENT_HANDLE:
                    // 解析接管参数
                    if (!parseAgentHandleParams(policyDecision, result)) {
                        // 解析失败 → 回退原生响铃
                        result.route = ROUTE_RING_NATIVE;
                        result.reason = "接管参数解析失败";
                        Slog.w(TAG, "Failed to parse agent handle params, fallback to native");
                        return result;
                    }
                    
                    // 模式授权检查
                    if (!isModeAuthorized(result.handleMode)) {
                        result.route = ROUTE_RING_NATIVE;
                        result.reason = "接管模式未授权";
                        Slog.w(TAG, "Handle mode not authorized: L" + result.handleMode);
                        return result;
                    }
                    
                    result.reason = buildHandleReason(result.handleMode, descriptor);
                    break;
                    
                case ROUTE_RECORD_ONLY:
                    // 解析 L3 参数
                    parseRecordOnlyParams(policyDecision, result);
                    result.reason = "仅记录 · 建议回拨";
                    break;
                    
                default:
                    // 未知路由类型 → 回退原生响铃
                    Slog.w(TAG, "Unknown route type: " + result.route);
                    result.route = ROUTE_RING_NATIVE;
                    result.reason = "未知路由类型";
                    result.ruleId = "sys.callgate.unknown_route";
                    return result;
            }
            
            // ==================== 阶段 4：统计更新 ====================
            updateStats(result);
            
            Slog.i(TAG, String.format("Route decision: %s → %s (rule=%s)",
                descriptor.numberMasked, getRouteName(result.route), result.ruleId));
            
            return result;
            
        } catch (Exception e) {
            // ==================== 异常兜底：fail-safe 到原生响铃 ====================
            Slog.e(TAG, "Exception in routeIncomingCall, fallback to native", e);
            result.route = ROUTE_RING_NATIVE;
            result.reason = "系统异常";
            result.ruleId = "sys.callgate.exception";
            return result;
        }
    }
    
    // ==================== 接管参数解析 ====================
    
    /**
     * 解析 AGENT_HANDLE 路由参数（L1/L2）
     * 
     * routeParams JSON 格式：
     * {
     *   "handleMode": 1,  // L1=1, L2=2
     *   "greetingTextRef": "greeting.default.v1",
     *   "asrModel": "small_local",
     *   "summaryModel": "local",
     *   "maxVoicemailSec": 60,
     *   "suggestCallback": true
     * }
     */
    private boolean parseAgentHandleParams(PolicyDecision decision, RoutingResult result) {
        try {
            if (decision.routeParams == null || decision.routeParams.isEmpty()) {
                // 使用默认参数
                result.handleMode = HANDLE_MODE_L1_SILENT_AI;
                result.greetingTextRef = "greeting.default.v1";
                return true;
            }
            
            JSONObject params = new JSONObject(decision.routeParams);
            
            result.handleMode = params.optInt("handleMode", HANDLE_MODE_L1_SILENT_AI);
            result.greetingTextRef = params.optString("greetingTextRef", "greeting.default.v1");
            result.asrModel = params.optString("asrModel", "small_local");
            result.summaryModel = params.optString("summaryModel", "local");
            result.maxVoicemailSec = params.optInt("maxVoicemailSec", 60);
            result.suggestCallback = params.optBoolean("suggestCallback", true);
            
            // 验证 handleMode 有效性
            if (result.handleMode != HANDLE_MODE_L1_SILENT_AI &&
                result.handleMode != HANDLE_MODE_L2_AUDIO_DIALOG) {
                Slog.w(TAG, "Invalid handleMode: " + result.handleMode);
                return false;
            }
            
            return true;
            
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to parse agent handle params", e);
            return false;
        }
    }
    
    /**
     * 解析 RECORD_ONLY 路由参数（L3）
     */
    private void parseRecordOnlyParams(PolicyDecision decision, RoutingResult result) {
        try {
            if (decision.routeParams == null || decision.routeParams.isEmpty()) {
                result.recommendCallback = true;
                result.callbackTimeHint = "";
                return;
            }
            
            JSONObject params = new JSONObject(decision.routeParams);
            result.recommendCallback = params.optBoolean("recommendCallback", true);
            result.callbackTimeHint = params.optString("callbackTimeHint", "");
            
        } catch (JSONException e) {
            Slog.w(TAG, "Failed to parse record only params", e);
            result.recommendCallback = true;
            result.callbackTimeHint = "";
        }
    }
    
    // ==================== 白名单管理 ====================
    
    /**
     * 检查号码是否在白名单中
     * 
     * 白名单来源：
     * - 通讯录联系人（由 telecom 预填充）
     * - 用户手动添加的号码/号段
     * - D6 实体关系（WORKS_AT/家庭成员等）
     */
    public boolean isWhitelisted(String numberE164) {
        if (numberE164 == null || numberE164.isEmpty()) {
            return false;
        }
        return whitelistCache.getOrDefault(numberE164, false);
    }
    
    /**
     * 添加白名单条目
     */
    public void addWhitelistEntry(String numberE164) {
        if (numberE164 != null && !numberE164.isEmpty()) {
            whitelistCache.put(numberE164, true);
            Slog.d(TAG, "Whitelist entry added: " + maskNumber(numberE164));
        }
    }
    
    /**
     * 移除白名单条目（紧急/系统号码不可删除）
     */
    public boolean removeWhitelistEntry(String numberE164, boolean isProtected) {
        if (isProtected) {
            Slog.w(TAG, "Cannot remove protected whitelist entry: " + maskNumber(numberE164));
            return false;
        }
        whitelistCache.remove(numberE164);
        Slog.d(TAG, "Whitelist entry removed: " + maskNumber(numberE164));
        return true;
    }
    
    /**
     * 清空白名单（保留系统保护条目）
     */
    public void clearWhitelist(Set<String> protectedNumbers) {
        whitelistCache.clear();
        if (protectedNumbers != null) {
            for (String num : protectedNumbers) {
                whitelistCache.put(num, true);
            }
        }
        Slog.i(TAG, "Whitelist cleared, protected entries restored: " + protectedNumbers.size());
    }
    
    // ==================== 接管模式管理 ====================
    
    /**
     * 设置接管模式（L1/L2/L3/关闭）
     * 
     * L1/L2 开启需用户授权位
     */
    public boolean setGateMode(int mode, boolean authorized) {
        if (mode < 0 || mode > HANDLE_MODE_L3_RECORD_ONLY) {
            Slog.w(TAG, "Invalid gate mode: " + mode);
            return false;
        }
        
        // L1/L2 需授权
        if ((mode == HANDLE_MODE_L1_SILENT_AI || mode == HANDLE_MODE_L2_AUDIO_DIALOG) 
            && !authorized) {
            Slog.w(TAG, "L1/L2 mode requires authorization");
            return false;
        }
        
        currentGateMode = mode;
        modeAuthorization.put(mode, authorized);
        
        Slog.i(TAG, "Gate mode updated: L" + mode + " (authorized=" + authorized + ")");
        return true;
    }
    
    /**
     * 获取当前接管模式
     */
    public int getGateMode() {
        return currentGateMode;
    }
    
    /**
     * 检查模式是否已授权
     */
    private boolean isModeAuthorized(int mode) {
        return modeAuthorization.getOrDefault(mode, false);
    }
    
    // ==================== 统计 ====================
    
    private void updateStats(RoutingResult result) {
        int count = dailyRouteCounts.getOrDefault(result.route, 0);
        dailyRouteCounts.put(result.route, count + 1);
    }
    
    private void initDailyCounts() {
        dailyRouteCounts.put(ROUTE_RING_NATIVE, 0);
        dailyRouteCounts.put(ROUTE_AGENT_HANDLE, 0);
        dailyRouteCounts.put(ROUTE_RECORD_ONLY, 0);
    }
    
    /**
     * 重置每日统计（由系统服务在午夜调用）
     */
    public void resetDailyStats() {
        initDailyCounts();
        Slog.i(TAG, "Daily stats reset");
    }
    
    /**
     * 获取统计数据
     */
    public Map<Integer, Integer> getDailyRouteCounts() {
        return new ConcurrentHashMap<>(dailyRouteCounts);
    }
    
    // ==================== 辅助方法 ====================
    
    private String buildHandleReason(int mode, CallDescriptor descriptor) {
        StringBuilder sb = new StringBuilder();
        
        if (mode == HANDLE_MODE_L1_SILENT_AI) {
            sb.append("AI接管 · 留言转文字");
        } else if (mode == HANDLE_MODE_L2_AUDIO_DIALOG) {
            sb.append("AI接管 · 语音对话");
        }
        
        if (TRUST_SPAM_HIGH.equals(descriptor.callerTrust)) {
            sb.append(" · 广告高置信");
        } else if (TRUST_UNKNOWN.equals(descriptor.callerTrust)) {
            sb.append(" · 未知号码");
        }
        
        return sb.toString();
    }
    
    private String getRouteName(int route) {
        switch (route) {
            case ROUTE_RING_NATIVE: return "RING_NATIVE";
            case ROUTE_AGENT_HANDLE: return "AGENT_HANDLE";
            case ROUTE_RECORD_ONLY: return "RECORD_ONLY";
            default: return "UNKNOWN(" + route + ")";
        }
    }
    
    /**
     * 号码掩码（仅用于日志，保护隐私）
     */
    private String maskNumber(String numberE164) {
        if (numberE164 == null || numberE164.length() < 4) {
            return "***";
        }
        int len = numberE164.length();
        return numberE164.substring(0, 3) + "****" + numberE164.substring(len - 4);
    }
}
