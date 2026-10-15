package com.android.server.genton;

import android.util.Slog;

import com.genton.execution.ActionRequest;
import com.genton.execution.TaintViolation;
import com.genton.policy.TaintMapEntry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图一致性检查器（Intent Consistency Checker）
 * 
 * 核心职责：
 * 1. 检测参数值与意图的一致性（防止提示注入攻击）
 * 2. 检测危险参数组合（如跨应用数据泄露）
 * 3. 检测时序异常（如过快的连续操作）
 * 4. 与 TaintValidator 配合，形成完整的参数校验链
 * 
 * 典型场景：
 * 1. 用户意图："给张三发消息"
 * 2. Agent 生成动作：send.message(target="李四", message="转账...")
 * 3. 检测到目标不一致 → DENY
 * 
 * 安全原则：
 * - 用户提到的实体必须与参数匹配
 * - 敏感操作（转账/删除）需显式确认
 * - 跨应用操作需检查上下文一致性
 * 
 * 草案来源：D2 §6 意图一致性
 */
public class IntentConsistencyChecker {
    private static final String TAG = "IntentConsistencyChecker";
    
    // 高风险关键词（需要严格一致性检查）
    private static final String[] HIGH_RISK_KEYWORDS = {
        "transfer", "pay", "send_money", "delete", "remove", "uninstall",
        "grant_permission", "disable_security", "format", "factory_reset"
    };
    
    // 敏感参数字段（需要与意图匹配）
    private static final String[] SENSITIVE_FIELDS = {
        "target", "recipient", "contact", "amount", "price", "packageName"
    };
    
    /**
     * 一致性冲突记录
     */
    public static class ConsistencyViolation {
        public String field;
        public String expectedValue;  // 意图中的值
        public String actualValue;    // 参数中的值
        public String reason;
        public String suggestedMitigation;
    }
    
    /**
     * 检查意图一致性
     * 
     * @param request 动作请求
     * @param userIntent 用户原始意图（文本或结构化）
     * @return 一致性冲突列表
     */
    public List<ConsistencyViolation> checkConsistency(ActionRequest request, String userIntent) {
        List<ConsistencyViolation> violations = new ArrayList<>();
        
        // 1. 检查是否为高风险动作
        boolean isHighRisk = isHighRiskAction(request.actionId);
        
        if (!isHighRisk && userIntent == null) {
            // 低风险动作且无意图上下文，跳过检查
            return violations;
        }
        
        try {
            // 2. 解析动作参数
            JSONObject args = new JSONObject(request.argumentsJson);
            
            // 3. 提取意图中的实体（简单关键词匹配）
            Map<String, String> intentEntities = extractEntities(userIntent);
            
            // 4. 检查敏感字段是否与意图匹配
            for (String field : SENSITIVE_FIELDS) {
                if (args.has(field)) {
                    String actualValue = args.getString(field);
                    String expectedValue = intentEntities.get(field);
                    
                    if (expectedValue != null && !isMatch(expectedValue, actualValue)) {
                        // 不匹配 → 可能是提示注入
                        ConsistencyViolation violation = new ConsistencyViolation();
                        violation.field = field;
                        violation.expectedValue = expectedValue;
                        violation.actualValue = actualValue;
                        violation.reason = String.format(
                            "Parameter '%s' mismatch: intent mentioned '%s', but action uses '%s'",
                            field, expectedValue, actualValue
                        );
                        violation.suggestedMitigation = isHighRisk ? 
                            "deny_execution" : "require_user_confirmation";
                        
                        violations.add(violation);
                        
                        Slog.w(TAG, "Intent consistency violation: " + violation.reason);
                    }
                }
            }
            
            // 5. 检查高风险动作的显式确认要求
            if (isHighRisk && !hasExplicitConfirmation(userIntent, request.actionId)) {
                ConsistencyViolation violation = new ConsistencyViolation();
                violation.field = "action";
                violation.expectedValue = "explicit_confirmation";
                violation.actualValue = "implicit";
                violation.reason = "High-risk action requires explicit user confirmation";
                violation.suggestedMitigation = "require_user_confirmation_with_details";
                
                violations.add(violation);
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to check intent consistency", e);
        }
        
        return violations;
    }
    
    /**
     * 检查跨应用数据流一致性
     * 
     * @param request 动作请求
     * @param contextAppPackage 当前前台应用包名
     * @return 是否存在跨应用风险
     */
    public boolean checkCrossAppConsistency(ActionRequest request, String contextAppPackage) {
        try {
            JSONObject args = new JSONObject(request.argumentsJson);
            
            // 检查目标应用是否与上下文一致
            if (args.has("targetPackage")) {
                String targetPackage = args.getString("targetPackage");
                
                // 如果目标应用与当前应用不一致，需要检查是否合理
                if (!targetPackage.equals(contextAppPackage)) {
                    // 检查是否为合理的跨应用操作（如分享）
                    if (!isLegitCrossAppAction(request.actionId)) {
                        Slog.w(TAG, String.format(
                            "Cross-app data flow detected: context=%s, target=%s",
                            contextAppPackage, targetPackage
                        ));
                        return false;
                    }
                }
            }
            
            return true;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to check cross-app consistency", e);
            return false;
        }
    }
    
    /**
     * 检查时序异常（防止自动化攻击）
     * 
     * @param request 动作请求
     * @param lastActionTimestamp 上一次动作时间戳
     * @return 是否存在时序异常
     */
    public boolean checkTemporalConsistency(ActionRequest request, long lastActionTimestamp) {
        long now = System.currentTimeMillis();
        long interval = now - lastActionTimestamp;
        
        // 检查是否为高频操作（< 100ms）
        if (interval < 100 && isHighRiskAction(request.actionId)) {
            Slog.w(TAG, "Temporal anomaly: high-risk action in " + interval + "ms");
            return false;
        }
        
        return true;
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 判断是否为高风险动作
     */
    private boolean isHighRiskAction(String actionId) {
        for (String keyword : HIGH_RISK_KEYWORDS) {
            if (actionId.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 从意图文本中提取实体（简单实现）
     */
    private Map<String, String> extractEntities(String intent) {
        Map<String, String> entities = new HashMap<>();
        
        if (intent == null || intent.isEmpty()) {
            return entities;
        }
        
        // 简单关键词匹配（生产环境应使用 NER 模型）
        
        // 匹配联系人名称
        if (intent.matches(".*给\\s*(\\S+)\\s*发.*")) {
            String name = intent.replaceAll(".*给\\s*(\\S+)\\s*发.*", "$1");
            entities.put("target", name);
            entities.put("recipient", name);
            entities.put("contact", name);
        }
        
        // 匹配金额
        if (intent.matches(".*转\\s*(\\d+)\\s*元.*")) {
            String amount = intent.replaceAll(".*转\\s*(\\d+)\\s*元.*", "$1");
            entities.put("amount", amount);
        }
        
        // 匹配应用名称
        if (intent.matches(".*打开\\s*(\\S+).*")) {
            String appName = intent.replaceAll(".*打开\\s*(\\S+).*", "$1");
            entities.put("packageName", appName);
        }
        
        return entities;
    }
    
    /**
     * 检查两个值是否匹配（支持模糊匹配）
     */
    private boolean isMatch(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        
        // 精确匹配
        if (expected.equals(actual)) {
            return true;
        }
        
        // 包含匹配（处理别名、简称）
        if (actual.contains(expected) || expected.contains(actual)) {
            return true;
        }
        
        // TODO: 添加更复杂的匹配逻辑（拼音、同义词等）
        
        return false;
    }
    
    /**
     * 检查用户意图中是否包含显式确认
     */
    private boolean hasExplicitConfirmation(String intent, String actionId) {
        if (intent == null) {
            return false;
        }
        
        // 检查确认关键词
        String[] confirmKeywords = {"确认", "确定", "是的", "好的", "同意"};
        for (String keyword : confirmKeywords) {
            if (intent.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查是否为合理的跨应用操作
     */
    private boolean isLegitCrossAppAction(String actionId) {
        // 分享、跳转等操作允许跨应用
        return actionId.contains("share") || 
               actionId.contains("open") || 
               actionId.contains("launch");
    }
    
    /**
     * 生成一致性冲突摘要
     */
    public String generateViolationSummary(List<ConsistencyViolation> violations) {
        if (violations.isEmpty()) {
            return "No consistency violations";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Intent consistency violations (").append(violations.size()).append("):\n");
        for (ConsistencyViolation v : violations) {
            sb.append("  - ").append(v.field)
              .append(": expected '").append(v.expectedValue)
              .append("', got '").append(v.actualValue)
              .append("' (mitigation: ").append(v.suggestedMitigation).append(")\n");
        }
        return sb.toString();
    }
}
