package com.android.server.genton;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * 动作注册表构建期校验工具（A3.1 动作注册表）
 * 
 * 职责：
 * - 验证 action-registry.json 符合 schema 约定
 * - 检查动作名唯一性与命名规范
 * - 验证 executorId 有效性
 * - 检查 auditEventType 存在性
 * - 验证 inputSource 来源约束
 * - 生成校验报告
 * 
 * 使用场景：
 * - CI 构建流程中的强制校验步骤
 * - 动作注册表更新时的合规检查
 * - 开发期本地验证
 * 
 * 命令行用法：
 *   java ActionRegistryValidator <registry-file> [audit-schema-file]
 * 
 * 返回码：
 *   0: 校验通过
 *   1: 校验失败
 *   2: 文件读取错误
 */
public class ActionRegistryValidator {
    
    private static final String TAG = "ActionRegistryValidator";
    
    // ==================== 合法执行器 ID 白名单 ====================
    private static final Set<String> VALID_EXECUTOR_IDS = new HashSet<>();
    static {
        VALID_EXECUTOR_IDS.add("activity");
        VALID_EXECUTOR_IDS.add("input");
        VALID_EXECUTOR_IDS.add("query");
        VALID_EXECUTOR_IDS.add("device_control");
        VALID_EXECUTOR_IDS.add("notification");
        VALID_EXECUTOR_IDS.add("clipboard");
        VALID_EXECUTOR_IDS.add("telephony");
        VALID_EXECUTOR_IDS.add("settings");
        VALID_EXECUTOR_IDS.add("system");
    }
    
    // ==================== 合法风险等级 ====================
    private static final Set<Integer> VALID_RISK_LEVELS = new HashSet<>();
    static {
        VALID_RISK_LEVELS.add(0);  // low
        VALID_RISK_LEVELS.add(1);  // medium
        VALID_RISK_LEVELS.add(2);  // high
    }
    
    // ==================== 合法来源类型 ====================
    private static final Set<String> VALID_SOURCE_TYPES = new HashSet<>();
    static {
        VALID_SOURCE_TYPES.add("trusted");
        VALID_SOURCE_TYPES.add("user");
        VALID_SOURCE_TYPES.add("any");
    }
    
    // ==================== 命名规范 ====================
    private static final Pattern ACTION_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final Pattern CAPABILITY_PATTERN = Pattern.compile("^agent\\.[a-z_]+$");
    private static final Pattern AUDIT_EVENT_PATTERN = Pattern.compile("^action\\.[a-z_]+$");
    
    // ==================== 校验结果 ====================
    
    public static class ValidationResult {
        public boolean success;
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public int actionCount;
        public Map<String, Integer> executorCounts = new HashMap<>();
        public Map<Integer, Integer> riskLevelCounts = new HashMap<>();
        
        public void addError(String message) {
            errors.add(message);
            success = false;
        }
        
        public void addWarning(String message) {
            warnings.add(message);
        }
        
        public String getReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("=".repeat(60)).append("\n");
            sb.append("动作注册表校验报告\n");
            sb.append("=".repeat(60)).append("\n\n");
            
            sb.append("校验结果: ").append(success ? "✅ 通过" : "❌ 失败").append("\n");
            sb.append("动作总数: ").append(actionCount).append("\n\n");
            
            if (!executorCounts.isEmpty()) {
                sb.append("执行器分布:\n");
                executorCounts.forEach((executor, count) -> 
                    sb.append("  - ").append(executor).append(": ").append(count).append("\n"));
                sb.append("\n");
            }
            
            if (!riskLevelCounts.isEmpty()) {
                sb.append("风险等级分布:\n");
                riskLevelCounts.forEach((level, count) -> {
                    String levelName = level == 0 ? "low" : (level == 1 ? "medium" : "high");
                    sb.append("  - ").append(levelName).append(": ").append(count).append("\n");
                });
                sb.append("\n");
            }
            
            if (!errors.isEmpty()) {
                sb.append("错误 (").append(errors.size()).append("):\n");
                for (int i = 0; i < errors.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(errors.get(i)).append("\n");
                }
                sb.append("\n");
            }
            
            if (!warnings.isEmpty()) {
                sb.append("警告 (").append(warnings.size()).append("):\n");
                for (int i = 0; i < warnings.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(warnings.get(i)).append("\n");
                }
                sb.append("\n");
            }
            
            sb.append("=".repeat(60)).append("\n");
            return sb.toString();
        }
    }
    
    // ==================== 校验逻辑 ====================
    
    /**
     * 校验动作注册表文件
     */
    public static ValidationResult validate(String registryFilePath, String auditSchemaFilePath) {
        ValidationResult result = new ValidationResult();
        result.success = true;
        
        try {
            // 读取注册表文件
            String registryContent = readFile(registryFilePath);
            JSONObject registry = new JSONObject(registryContent);
            
            // C1: 验证顶层结构
            if (!registry.has("version")) {
                result.addError("缺少 version 字段");
            }
            
            if (!registry.has("actions")) {
                result.addError("缺少 actions 字段");
                return result;
            }
            
            // 读取审计事件类型（如果提供）
            Set<String> validAuditEventTypes = new HashSet<>();
            if (auditSchemaFilePath != null) {
                validAuditEventTypes = loadAuditEventTypes(auditSchemaFilePath);
            }
            
            // C2: 验证每个动作
            JSONArray actions = registry.getJSONArray("actions");
            result.actionCount = actions.length();
            
            Set<String> actionNames = new HashSet<>();
            
            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.getJSONObject(i);
                String actionName = action.optString("name", "");
                
                // 验证单个动作
                validateAction(action, i, actionNames, validAuditEventTypes, result);
                
                // 统计
                String executorId = action.optString("executorId", "");
                result.executorCounts.put(executorId, 
                    result.executorCounts.getOrDefault(executorId, 0) + 1);
                
                int riskLevel = action.optInt("riskLevel", -1);
                if (riskLevel >= 0) {
                    result.riskLevelCounts.put(riskLevel, 
                        result.riskLevelCounts.getOrDefault(riskLevel, 0) + 1);
                }
            }
            
            // C3: 全局一致性检查
            if (actions.length() == 0) {
                result.addWarning("注册表为空");
            }
            
        } catch (JSONException e) {
            result.addError("JSON 解析失败: " + e.getMessage());
        } catch (IOException e) {
            result.addError("文件读取失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 验证单个动作
     */
    private static void validateAction(JSONObject action, int index, Set<String> actionNames,
                                      Set<String> validAuditEventTypes, ValidationResult result) {
        String prefix = "actions[" + index + "]";
        
        // C1: 必填字段检查
        String[] requiredFields = {"name", "displayName", "executorId", "riskLevel", 
                                   "requiredCapability", "inputSchema", "outputSchema", "auditEventType"};
        for (String field : requiredFields) {
            if (!action.has(field)) {
                result.addError(prefix + " 缺少必填字段: " + field);
            }
        }
        
        // C2: name 验证
        String name = action.optString("name", "");
        if (name.isEmpty()) {
            result.addError(prefix + " name 不能为空");
        } else {
            // 唯一性检查
            if (actionNames.contains(name)) {
                result.addError(prefix + " name 重复: " + name);
            } else {
                actionNames.add(name);
            }
            
            // 命名规范检查
            if (!ACTION_NAME_PATTERN.matcher(name).matches()) {
                result.addError(prefix + " name 不符合命名规范（需为小写命名空间格式）: " + name);
            }
        }
        
        // C3: displayName 验证
        String displayName = action.optString("displayName", "");
        if (displayName.isEmpty()) {
            result.addError(prefix + " displayName 不能为空");
        } else if (displayName.length() > 64) {
            result.addError(prefix + " displayName 超过 64 字符: " + displayName);
        }
        
        // C4: executorId 验证
        String executorId = action.optString("executorId", "");
        if (!VALID_EXECUTOR_IDS.contains(executorId)) {
            result.addError(prefix + " executorId 无效: " + executorId + 
                " (合法值: " + VALID_EXECUTOR_IDS + ")");
        }
        
        // C5: riskLevel 验证
        if (!action.has("riskLevel")) {
            result.addError(prefix + " 缺少 riskLevel");
        } else {
            int riskLevel = action.optInt("riskLevel", -1);
            if (!VALID_RISK_LEVELS.contains(riskLevel)) {
                result.addError(prefix + " riskLevel 无效: " + riskLevel + " (合法值: 0, 1, 2)");
            }
        }
        
        // C6: requiredCapability 验证
        String capability = action.optString("requiredCapability", "");
        if (!CAPABILITY_PATTERN.matcher(capability).matches()) {
            result.addError(prefix + " requiredCapability 格式错误: " + capability + 
                " (需匹配 agent.xxx)");
        }
        
        // C7: inputSource 验证
        if (action.has("inputSource")) {
            try {
                JSONObject inputSource = action.getJSONObject("inputSource");
                for (String key : inputSource.keySet()) {
                    String sourceType = inputSource.getString(key);
                    if (!VALID_SOURCE_TYPES.contains(sourceType)) {
                        result.addError(prefix + " inputSource[" + key + "] 类型无效: " + sourceType +
                            " (合法值: " + VALID_SOURCE_TYPES + ")");
                    }
                }
            } catch (JSONException e) {
                result.addError(prefix + " inputSource 格式错误: " + e.getMessage());
            }
        }
        
        // C8: inputSchema 验证
        if (action.has("inputSchema")) {
            try {
                JSONObject inputSchema = action.getJSONObject("inputSchema");
                if (!inputSchema.has("type") || !"object".equals(inputSchema.getString("type"))) {
                    result.addError(prefix + " inputSchema.type 必须为 'object'");
                }
                if (!inputSchema.has("properties")) {
                    result.addError(prefix + " inputSchema 缺少 properties");
                }
            } catch (JSONException e) {
                result.addError(prefix + " inputSchema 格式错误: " + e.getMessage());
            }
        }
        
        // C9: outputSchema 验证
        if (action.has("outputSchema")) {
            try {
                JSONObject outputSchema = action.getJSONObject("outputSchema");
                if (!outputSchema.has("type") || !"object".equals(outputSchema.getString("type"))) {
                    result.addError(prefix + " outputSchema.type 必须为 'object'");
                }
            } catch (JSONException e) {
                result.addError(prefix + " outputSchema 格式错误: " + e.getMessage());
            }
        }
        
        // C10: auditEventType 验证
        String auditEventType = action.optString("auditEventType", "");
        if (!AUDIT_EVENT_PATTERN.matcher(auditEventType).matches()) {
            result.addError(prefix + " auditEventType 格式错误: " + auditEventType +
                " (需匹配 action.xxx)");
        } else if (!validAuditEventTypes.isEmpty() && !validAuditEventTypes.contains(auditEventType)) {
            result.addError(prefix + " auditEventType 在审计 schema 中不存在: " + auditEventType);
        }
        
        // C11: 高风险动作必须声明 inputSource
        if (action.optInt("riskLevel", 0) == 2) {
            if (!action.has("inputSource") || action.getJSONObject("inputSource").length() == 0) {
                result.addWarning(prefix + " 高风险动作建议声明 inputSource 来源约束");
            }
        }
        
        // C12: deprecated 检查
        if (action.optBoolean("deprecated", false)) {
            if (!action.has("replacedBy")) {
                result.addWarning(prefix + " 已废弃动作建议指定 replacedBy");
            }
        }
    }
    
    /**
     * 从审计 schema 加载合法事件类型
     */
    private static Set<String> loadAuditEventTypes(String auditSchemaPath) {
        Set<String> eventTypes = new HashSet<>();
        // TODO: 实现审计 schema 解析
        // 暂时返回空集合，后续与 A5.2 审计服务端集成
        return eventTypes;
    }
    
    /**
     * 读取文件内容
     */
    private static String readFile(String filePath) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }
    
    // ==================== 命令行入口 ====================
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: ActionRegistryValidator <registry-file> [audit-schema-file]");
            System.exit(2);
        }
        
        String registryFile = args[0];
        String auditSchemaFile = args.length > 1 ? args[1] : null;
        
        System.out.println("验证动作注册表: " + registryFile);
        if (auditSchemaFile != null) {
            System.out.println("审计 schema: " + auditSchemaFile);
        }
        System.out.println();
        
        ValidationResult result = validate(registryFile, auditSchemaFile);
        
        System.out.println(result.getReport());
        
        System.exit(result.success ? 0 : 1);
    }
}
