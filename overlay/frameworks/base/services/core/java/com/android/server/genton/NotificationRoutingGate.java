package com.android.server.genton;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知可见性门路由执行器（A7.3）
 * 
 * 消费策略引擎的 notification 域路由决策，执行通知栈路由：
 * - P0_NATIVE: 原生弹窗
 * - P1_DIGEST: Agent 静默读→简报卡
 * - P2_AGENT_ACT: Agent 代为处理
 * 
 * 调用时机：通知进栈后、SystemUI 呈现前（≤20ms）
 */
public class NotificationRoutingGate {
    private static final String TAG = "NotificationRoutingGate";
    
    // ==================== 路由结果 ====================
    
    public static class RoutingResult {
        /** 路由决策 */
        public int route;
        
        /** 匹配的规则 ID */
        public String ruleId;
        
        /** 策略版本 */
        public String policyVersion;
        
        /** 摘要模型（P1 使用） */
        public String summaryModel;  // local_tiny
        
        /** 聚合窗口（毫秒） */
        public long aggregateWindowMs;
        
        /** DND 结束后才显示 */
        public boolean holdUntilDndEnd;
        
        /** 抑制角标 */
        public boolean suppressBadge;
        
        /** Agent 动作（P2 使用） */
        public String agentAct;  // fold/dismiss
        
        /** 发送方授权策略 */
        public String senderAuthz;  // one_per_sender
        
        /** 日处理上限 */
        public int dailyHandleCap;
        
        /** 审计级别 */
        public String auditLevel;
        
        /** 决策时间戳 */
        public long decidedAtMs;
        
        public RoutingResult(int route, String ruleId) {
            this.route = route;
            this.ruleId = ruleId;
            this.decidedAtMs = System.currentTimeMillis();
        }
    }
    
    // ==================== 通知描述符 ====================
    
    public static class NotificationDescriptor {
        /** 通知 ID */
        public String notificationId;
        
        /** 发送方包名 */
        public String senderPackage;
        
        /** 发送方域 */
        public String senderDomain;
        
        /** 发送方指纹（SHA256） */
        public String senderFingerprint;
        
        /** 发送方信任等级 */
        public String senderTrust;  // SYSTEM_APP/TRUSTED_PARTNER/APP/UNKNOWN
        
        /** 分类类别 */
        public String category;  // otp/payment_result/emergency/alarm/news/advertising 等
        
        /** 分类器置信度 */
        public double classifierConfidence;
        
        /** 用户钉选 P0 */
        public boolean userPinnedP0;
        
        /** 通知标题 */
        public String title;
        
        /** 通知内容 */
        public String content;
        
        /** 时间戳 */
        public long timestampMs;
    }
    
    // ==================== 路由统计（配额控制）====================
    
    private static class RoutingStats {
        public int dailyP1Count = 0;
        public int dailyP2Count = 0;
        public long lastResetDay = 0;
        
        public void checkAndReset() {
            long currentDay = System.currentTimeMillis() / (24 * 3600 * 1000);
            if (currentDay != lastResetDay) {
                dailyP1Count = 0;
                dailyP2Count = 0;
                lastResetDay = currentDay;
            }
        }
    }
    
    private final RoutingStats mStats = new RoutingStats();
    private final ConcurrentHashMap<String, Boolean> mUserPinnedP0 = new ConcurrentHashMap<>();
    
    // ==================== 路由决策入口 ====================
    
    /**
     * 执行通知路由决策
     * 
     * @param descriptor 通知描述符
     * @param policyDecision 策略引擎裁决结果
     * @return 路由结果
     */
    public RoutingResult routeNotification(NotificationDescriptor descriptor, PolicyDecision policyDecision) {
        // 参数验证
        if (descriptor == null || policyDecision == null) {
            Log.e(TAG, "Invalid parameters: descriptor or policyDecision is null");
            return createDefaultRoute(descriptor);
        }
        
        // 检查是否为通知路由决策
        if (!policyDecision.isNotificationRoute()) {
            Log.w(TAG, "PolicyDecision is not a notification route, falling back to default");
            return createDefaultRoute(descriptor);
        }
        
        // 检查 baseline deny（用户钉选 P0）
        if (descriptor.userPinnedP0) {
            Log.i(TAG, "User pinned P0, forcing P0_NATIVE route");
            RoutingResult result = new RoutingResult(PolicyDecision.ROUTE_P0_NATIVE, "sys.notify.baseline.p0-pinned");
            result.policyVersion = policyDecision.policyVersion;
            result.auditLevel = "full";
            return result;
        }
        
        // 创建路由结果
        RoutingResult result = new RoutingResult(policyDecision.route, policyDecision.ruleId);
        result.policyVersion = policyDecision.policyVersion;
        result.auditLevel = policyDecision.auditLevel;
        
        // 解析路由参数
        if (policyDecision.routeParams != null) {
            parseRouteParams(policyDecision.routeParams, result);
        }
        
        // 配额检查
        if (!checkQuota(result)) {
            Log.w(TAG, "Daily quota exceeded, downgrading to P0_NATIVE");
            result.route = PolicyDecision.ROUTE_P0_NATIVE;
            result.ruleId = "quota_exceeded";
        }
        
        // 更新统计
        updateStats(result);
        
        Log.i(TAG, String.format("Routed notification %s: route=%s, ruleId=%s", 
                                 descriptor.notificationId, 
                                 getRouteName(result.route), 
                                 result.ruleId));
        
        return result;
    }
    
    /**
     * 解析路由参数
     */
    private void parseRouteParams(String routeParamsJson, RoutingResult result) {
        try {
            JSONObject params = new JSONObject(routeParamsJson);
            
            // P1 参数
            if (params.has("summaryModel")) {
                result.summaryModel = params.getString("summaryModel");
            }
            
            if (params.has("aggregateWindowMs")) {
                result.aggregateWindowMs = params.getLong("aggregateWindowMs");
            }
            
            if (params.has("holdUntilDndEnd")) {
                result.holdUntilDndEnd = params.getBoolean("holdUntilDndEnd");
            }
            
            if (params.has("suppressBadge")) {
                result.suppressBadge = params.getBoolean("suppressBadge");
            }
            
            // P2 参数
            if (params.has("agentAct")) {
                result.agentAct = params.getString("agentAct");
            }
            
            if (params.has("senderAuthz")) {
                result.senderAuthz = params.getString("senderAuthz");
            }
            
            if (params.has("dailyHandleCap")) {
                result.dailyHandleCap = params.getInt("dailyHandleCap");
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse route params: " + e.getMessage());
        }
    }
    
    /**
     * 配额检查
     */
    private boolean checkQuota(RoutingResult result) {
        mStats.checkAndReset();
        
        if (result.route == PolicyDecision.ROUTE_P1_DIGEST && result.dailyHandleCap > 0) {
            if (mStats.dailyP1Count >= result.dailyHandleCap) {
                return false;
            }
        }
        
        if (result.route == PolicyDecision.ROUTE_P2_AGENT_ACT && result.dailyHandleCap > 0) {
            if (mStats.dailyP2Count >= result.dailyHandleCap) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 更新统计
     */
    private void updateStats(RoutingResult result) {
        if (result.route == PolicyDecision.ROUTE_P1_DIGEST) {
            mStats.dailyP1Count++;
        } else if (result.route == PolicyDecision.ROUTE_P2_AGENT_ACT) {
            mStats.dailyP2Count++;
        }
    }
    
    /**
     * 创建默认路由（P0_NATIVE - 安全可见）
     */
    private RoutingResult createDefaultRoute(NotificationDescriptor descriptor) {
        RoutingResult result = new RoutingResult(PolicyDecision.ROUTE_P0_NATIVE, "default_fallback");
        result.auditLevel = "summary";
        return result;
    }
    
    /**
     * 获取路由名称
     */
    private String getRouteName(int route) {
        switch (route) {
            case PolicyDecision.ROUTE_P0_NATIVE:
                return "P0_NATIVE";
            case PolicyDecision.ROUTE_P1_DIGEST:
                return "P1_DIGEST";
            case PolicyDecision.ROUTE_P2_AGENT_ACT:
                return "P2_AGENT_ACT";
            default:
                return "UNKNOWN(" + route + ")";
        }
    }
    
    // ==================== 用户钉选 P0 管理 ====================
    
    /**
     * 设置用户钉选 P0
     */
    public void setPinnedP0(String senderKey, boolean pinned) {
        if (pinned) {
            mUserPinnedP0.put(senderKey, true);
        } else {
            mUserPinnedP0.remove(senderKey);
        }
        
        Log.i(TAG, String.format("User %s P0 for sender: %s", 
                                 pinned ? "pinned" : "unpinned", 
                                 senderKey));
    }
    
    /**
     * 检查是否钉选 P0
     */
    public boolean isPinnedP0(String senderKey) {
        return mUserPinnedP0.getOrDefault(senderKey, false);
    }
    
    /**
     * 清除所有钉选
     */
    public void clearAllPinned() {
        mUserPinnedP0.clear();
        Log.i(TAG, "Cleared all pinned P0 entries");
    }
    
    // ==================== 统计查询 ====================
    
    /**
     * 获取今日路由统计
     */
    public String getRoutingStats() {
        mStats.checkAndReset();
        return String.format("Daily stats: P1=%d, P2=%d", 
                             mStats.dailyP1Count, 
                             mStats.dailyP2Count);
    }
}
