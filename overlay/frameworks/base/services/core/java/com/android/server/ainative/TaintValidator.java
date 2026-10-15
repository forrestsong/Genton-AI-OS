package com.android.server.ainative;

import android.util.Slog;

import com.ainative.execution.ActionRequest;
import com.ainative.execution.TaintSource;
import com.ainative.execution.TaintViolation;
import com.ainative.policy.TaintMapEntry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Taint 验证器（Taint Validator）
 * 
 * 核心职责：
 * 1. 检查动作参数来源是否满足策略要求
 * 2. 检测 Taint 冲突（实际来源信任等级 > 策略要求）
 * 3. 生成缓解建议
 * 
 * 安全原则：
 * - 信任等级：TRUSTED_SYSTEM (0) > USER (1) > AGENT (2) > EXTERNAL (3)
 * - 低信任等级不能用于高风险参数
 * - 冲突 = DENY 或 REQUIRE_CONFIRMATION
 * 
 * 典型场景：
 * 1. ui.type 动作要求 text 参数来自 USER
 * 2. 实际参数来自 EXTERNAL（通知内容）
 * 3. 返回 TaintViolation，要求用户确认
 * 
 * 草案来源：D2 §6 Taint 机制
 */
public class TaintValidator {
    private static final String TAG = "TaintValidator";
    
    // 动作注册表（从 action_registry.json 加载）
    private final Map<String, ActionTaintPolicy> mActionPolicies;
    
    /**
     * 动作 Taint 策略（从注册表 inputSource 字段解析）
     */
    private static class ActionTaintPolicy {
        String actionId;
        Map<String, Integer> requiredLevels;  // argumentKey → requiredLevel
        
        ActionTaintPolicy(String actionId) {
            this.actionId = actionId;
            this.requiredLevels = new HashMap<>();
        }
    }
    
    public TaintValidator() {
        mActionPolicies = new HashMap<>();
        loadActionPolicies();
    }
    
    /**
     * 从动作注册表加载 Taint 策略
     * 
     * 示例（action_registry.json）：
     * {
     *   "name": "ui.type",
     *   "inputSource": {
     *     "text": "USER"  // 要求 text 参数来自 USER 或更高等级
     *   }
     * }
     */
    private void loadActionPolicies() {
        // 硬编码典型动作的 Taint 策略（生产环境应从 JSON 加载）
        
        // ui.type - 输入文本（高风险，要求 USER 等级）
        ActionTaintPolicy uiType = new ActionTaintPolicy("ui.type");
        uiType.requiredLevels.put("text", TaintSource.USER);
        mActionPolicies.put("ui.type", uiType);
        
        // app.launch - 启动应用（中风险，接受 AGENT 等级）
        ActionTaintPolicy appLaunch = new ActionTaintPolicy("app.launch");
        appLaunch.requiredLevels.put("packageName", TaintSource.AGENT);
        mActionPolicies.put("app.launch", appLaunch);
        
        // notification.reply - 通知回复（高风险，要求 USER 等级）
        ActionTaintPolicy notificationReply = new ActionTaintPolicy("notification.reply");
        notificationReply.requiredLevels.put("replyText", TaintSource.USER);
        mActionPolicies.put("notification.reply", notificationReply);
        
        // query.* - 查询类动作（低风险，接受任意等级）
        // 无需添加策略，默认放行
        
        Slog.i(TAG, "Loaded " + mActionPolicies.size() + " action taint policies");
    }
    
    /**
     * 验证动作请求的 Taint 来源
     * 
     * @param request 动作请求
     * @return Taint 冲突列表（空列表 = 无冲突）
     */
    public List<TaintViolation> validate(ActionRequest request) {
        List<TaintViolation> violations = new ArrayList<>();
        
        // 1. 获取动作的 Taint 策略
        ActionTaintPolicy policy = mActionPolicies.get(request.actionId);
        if (policy == null) {
            // 动作未定义 Taint 策略，默认放行
            return violations;
        }
        
        // 2. 构建参数来源映射
        Map<String, Integer> actualSources = new HashMap<>();
        if (request.argTaints != null) {
            for (TaintMapEntry entry : request.argTaints) {
                actualSources.put(entry.argumentKey, entry.sourceLevel);
            }
        }
        
        // 3. 检查每个受限参数
        for (Map.Entry<String, Integer> requirement : policy.requiredLevels.entrySet()) {
            String argKey = requirement.getKey();
            int requiredLevel = requirement.getValue();
            
            // 获取实际来源等级
            Integer actualLevel = actualSources.get(argKey);
            if (actualLevel == null) {
                // 参数未标记来源，默认为 AGENT 等级
                actualLevel = TaintSource.AGENT;
                Slog.w(TAG, "Argument '" + argKey + "' has no taint marking, assuming AGENT level");
            }
            
            // 4. 检测冲突（实际等级 > 要求等级 = 信任不足）
            if (actualLevel > requiredLevel) {
                TaintViolation violation = new TaintViolation();
                violation.argumentKey = argKey;
                violation.requiredLevel = requiredLevel;
                violation.actualLevel = actualLevel;
                violation.message = String.format(
                    "Argument '%s' requires trust level %s (%d), but got %s (%d)",
                    argKey,
                    getLevelName(requiredLevel),
                    requiredLevel,
                    getLevelName(actualLevel),
                    actualLevel
                );
                violation.suggestedMitigation = determineMitigation(requiredLevel, actualLevel);
                
                violations.add(violation);
                
                Slog.w(TAG, "Taint violation detected: " + violation.message);
            }
        }
        
        return violations;
    }
    
    /**
     * 快速检查（仅返回是否有冲突）
     */
    public boolean hasViolation(ActionRequest request) {
        return !validate(request).isEmpty();
    }
    
    /**
     * 确定缓解措施
     * 
     * @param requiredLevel 要求的信任等级
     * @param actualLevel 实际的信任等级
     * @return 缓解措施建议
     */
    private String determineMitigation(int requiredLevel, int actualLevel) {
        int trustGap = actualLevel - requiredLevel;
        
        if (trustGap == 1) {
            // 差距 1 级：要求用户确认
            return "require_user_confirmation";
        } else if (trustGap == 2) {
            // 差距 2 级：要求用户确认 + 显示来源
            return "require_user_confirmation_with_source_disclosure";
        } else {
            // 差距 3 级：拒绝执行
            return "deny_execution";
        }
    }
    
    /**
     * 获取信任等级名称
     */
    private String getLevelName(int level) {
        switch (level) {
            case TaintSource.TRUSTED_SYSTEM:
                return "TRUSTED_SYSTEM";
            case TaintSource.USER:
                return "USER";
            case TaintSource.AGENT:
                return "AGENT";
            case TaintSource.EXTERNAL:
                return "EXTERNAL";
            default:
                return "UNKNOWN";
        }
    }
    
    /**
     * 为动作请求添加默认 Taint 标记（如果缺失）
     * 
     * @param request 动作请求
     * @param defaultLevel 默认信任等级
     */
    public void applyDefaultTaints(ActionRequest request, int defaultLevel) {
        if (request.argTaints == null || request.argTaints.length == 0) {
            // 解析参数 JSON，为每个参数添加默认 Taint
            try {
                JSONObject args = new JSONObject(request.argumentsJson);
                List<TaintMapEntry> taints = new ArrayList<>();
                
                for (String key : args.keys()) {
                    TaintMapEntry entry = new TaintMapEntry();
                    entry.argumentKey = key;
                    entry.sourceLevel = defaultLevel;
                    taints.add(entry);
                }
                
                request.argTaints = taints.toArray(new TaintMapEntry[0]);
                
                Slog.d(TAG, "Applied default taint level " + getLevelName(defaultLevel) + 
                       " to " + taints.size() + " arguments");
            } catch (Exception e) {
                Slog.e(TAG, "Failed to apply default taints", e);
            }
        }
    }
    
    /**
     * 生成 Taint 冲突摘要（供审计/日志）
     */
    public String generateViolationSummary(List<TaintViolation> violations) {
        if (violations.isEmpty()) {
            return "No taint violations";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Taint violations (").append(violations.size()).append("):\n");
        for (TaintViolation v : violations) {
            sb.append("  - ").append(v.argumentKey)
              .append(": requires ").append(getLevelName(v.requiredLevel))
              .append(", got ").append(getLevelName(v.actualLevel))
              .append(" (mitigation: ").append(v.suggestedMitigation).append(")\n");
        }
        return sb.toString();
    }
}
