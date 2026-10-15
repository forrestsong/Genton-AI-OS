package com.android.server.ainative;

/**
 * 策略决策结果（A7.3 路由轴执行器接口）
 * 
 * PolicyDecision 是策略引擎裁决的输出，包含两轴决策：
 * - 授权轴：allow/deny/require_confirmation/require_mitigation
 * - 路由轴：notification 和 call_gate 域的路由指令
 * 
 * 路由决策由确定性执行器消费（SystemUI 通知可见性门 / CallGate 服务）
 */
public class PolicyDecision {
    
    // ==================== 授权轴决策（既有）====================
    public static final int DECISION_GRANT = 0;
    public static final int DECISION_DENY = 1;
    public static final int DECISION_REQUIRE_CONFIRMATION = 2;
    public static final int DECISION_REQUIRE_MITIGATION = 3;
    
    // ==================== 通知路由常量（notification 域）====================
    public static final int ROUTE_P0_NATIVE = 0;           // 原生弹窗
    public static final int ROUTE_P1_DIGEST = 1;           // Agent 静默读→简报
    public static final int ROUTE_P2_AGENT_ACT = 2;        // Agent 代为处理
    
    // ==================== 通话路由常量（call_gate 域）====================
    public static final int ROUTE_RING_NATIVE = 10;        // 原生响铃
    public static final int ROUTE_AGENT_HANDLE = 11;       // AI 接管（L1/L2）
    public static final int ROUTE_RECORD_ONLY = 12;        // 仅记录（L3）
    
    // ==================== 基础字段 ====================
    
    /** 决策类型（授权轴） */
    public int decision;
    
    /** 匹配的规则 ID */
    public String ruleId;
    
    /** 策略包版本 */
    public String policyVersion;
    
    /** 决策理由（可审计） */
    public String reason;
    
    /** 风险等级 */
    public String riskLevel;  // low/medium/high
    
    /** 审计级别 */
    public String auditLevel; // full/summary/none
    
    // ==================== 路由轴字段（notification/call_gate）====================
    
    /** 路由决策（路由轴） */
    public int route = -1;  // -1 表示无路由决策（纯授权轴）
    
    /** 策略域 */
    public String domain;  // action/notification/call_gate
    
    /** 路由参数（JSON 格式，包含 then 子句的路由配置） */
    public String routeParams;
    
    // ==================== 确认相关（授权轴）====================
    
    /** 确认 ID（REQUIRE_CONFIRMATION 时使用） */
    public String confirmId;
    
    /** 确认策略 */
    public String confirmationPolicy;  // always/one_per_intent/never
    
    // ==================== 时间戳 ====================
    
    /** 决策时间戳 */
    public long decidedAtMs;
    
    /** 决策有效期（毫秒） */
    public long validUntilMs;
    
    // ==================== 构造函数 ====================
    
    public PolicyDecision() {
        this.decidedAtMs = System.currentTimeMillis();
    }
    
    public PolicyDecision(int decision, String ruleId) {
        this();
        this.decision = decision;
        this.ruleId = ruleId;
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 是否为路由决策
     */
    public boolean hasRouteDecision() {
        return route >= 0;
    }
    
    /**
     * 是否为通知路由
     */
    public boolean isNotificationRoute() {
        return "notification".equals(domain) && route >= 0;
    }
    
    /**
     * 是否为通话路由
     */
    public boolean isCallGateRoute() {
        return "call_gate".equals(domain) && route >= 0;
    }
    
    /**
     * 获取路由名称（用于日志）
     */
    public String getRouteName() {
        if (!hasRouteDecision()) {
            return "NONE";
        }
        
        switch (route) {
            // Notification routes
            case ROUTE_P0_NATIVE:
                return "P0_NATIVE";
            case ROUTE_P1_DIGEST:
                return "P1_DIGEST";
            case ROUTE_P2_AGENT_ACT:
                return "P2_AGENT_ACT";
            
            // CallGate routes
            case ROUTE_RING_NATIVE:
                return "RING_NATIVE";
            case ROUTE_AGENT_HANDLE:
                return "AGENT_HANDLE";
            case ROUTE_RECORD_ONLY:
                return "RECORD_ONLY";
            
            default:
                return "UNKNOWN(" + route + ")";
        }
    }
    
    /**
     * 获取决策名称（用于日志）
     */
    public String getDecisionName() {
        switch (decision) {
            case DECISION_GRANT:
                return "GRANT";
            case DECISION_DENY:
                return "DENY";
            case DECISION_REQUIRE_CONFIRMATION:
                return "REQUIRE_CONFIRMATION";
            case DECISION_REQUIRE_MITIGATION:
                return "REQUIRE_MITIGATION";
            default:
                return "UNKNOWN(" + decision + ")";
        }
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PolicyDecision{");
        sb.append("decision=").append(getDecisionName());
        sb.append(", ruleId='").append(ruleId).append('\'');
        
        if (hasRouteDecision()) {
            sb.append(", domain='").append(domain).append('\'');
            sb.append(", route=").append(getRouteName());
        }
        
        if (reason != null) {
            sb.append(", reason='").append(reason).append('\'');
        }
        
        sb.append(", decidedAtMs=").append(decidedAtMs);
        sb.append('}');
        
        return sb.toString();
    }
}
