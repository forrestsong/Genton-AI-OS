package com.ainative.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * DMI 资产声明校验器（对齐 F2.1 schema v1.1 + 存在性规则 EXIST-001~009）
 * 
 * 用途：
 * 1. 本地开发时验证 DMI 声明合规性
 * 2. CI 流程中自动检查（对齐 A7.2 Policy DSL 校验风格）
 * 3. 集成到 A7.2 编译流水线
 * 
 * 使用：
 *   javac -cp snakeyaml.jar:json.jar DmiValidator.java
 *   java -cp .:snakeyaml.jar:json.jar com.ainative.tools.DmiValidator <dmi-file.yaml>
 */
public class DmiValidator {
    
    private static final String SCHEMA_VERSION = "1.1.0";
    private static final Pattern PACKAGE_NAME_PATTERN = 
        Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*\\.[a-zA-Z][a-zA-Z0-9_.]*$");
    private static final Pattern VERSION_PATTERN = 
        Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[a-z0-9]+)?$");
    private static final Pattern OPENAPI_VERSION_PATTERN = 
        Pattern.compile("^3\\.0\\.");
    private static final Pattern OPERATION_ID_PATTERN = 
        Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");
    private static final Pattern FALLBACK_PATTERN = 
        Pattern.compile("^(dmi|macro|skill|system):[a-zA-Z0-9_-]+$");
    private static final Pattern CAPABILITY_PATTERN = 
        Pattern.compile("^[a-z][a-z0-9_:-]{2,}$");
    
    private static final Set<String> VALID_CATEGORIES = new HashSet<>(Arrays.asList(
        "weather", "calendar", "communication", "media", "navigation", "shopping",
        "finance", "health", "productivity", "entertainment", "system", "utilities",
        "education", "social", "other"
    ));
    
    private static final Set<String> VALID_SECURITY_LEVELS = new HashSet<>(Arrays.asList(
        "L0", "L1", "L2"
    ));
    
    private static final Set<String> VALID_BRIDGE_TYPES = new HashSet<>(Arrays.asList(
        "system", "app", "http", "intent", "content_provider", "aidl"
    ));
    
    private static final Set<String> VALID_HTTP_METHODS = new HashSet<>(Arrays.asList(
        "GET", "POST", "PUT", "DELETE", "PATCH"
    ));
    
    private int errorCount = 0;
    private int warningCount = 0;
    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: DmiValidator <dmi-file.yaml|json> [--report <output.md>]");
            System.exit(1);
        }
        
        String inputFile = args[0];
        String reportFile = null;
        
        // 解析参数
        for (int i = 1; i < args.length; i++) {
            if ("--report".equals(args[i]) && i + 1 < args.length) {
                reportFile = args[i + 1];
                i++;
            }
        }
        
        DmiValidator validator = new DmiValidator();
        boolean valid = validator.validate(inputFile);
        
        // 生成报告
        if (reportFile != null) {
            validator.generateReport(reportFile, inputFile);
        }
        
        System.exit(valid ? 0 : 1);
    }
    
    public boolean validate(String filePath) {
        System.out.println("========================================");
        System.out.println("DMI 资产声明校验器 v" + SCHEMA_VERSION);
        System.out.println("========================================");
        System.out.println();
        System.out.println("校验文件: " + filePath);
        System.out.println();
        
        try {
            // 加载文件
            JSONObject dmi = loadDmiFile(filePath);
            
            // 执行校验
            validateOpenApiVersion(dmi);
            validateInfo(dmi);
            validatePaths(dmi);
            validateExistenceRules(dmi);
            
            // 输出结果
            printResults();
            
            return errorCount == 0;
            
        } catch (Exception e) {
            error("文件加载失败: " + e.getMessage());
            e.printStackTrace();
            printResults();
            return false;
        }
    }
    
    private JSONObject loadDmiFile(String filePath) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(filePath)));
        
        if (filePath.endsWith(".yaml") || filePath.endsWith(".yml")) {
            // YAML 转 JSON
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(content);
            return new JSONObject(map);
        } else if (filePath.endsWith(".json")) {
            return new JSONObject(new JSONTokener(content));
        } else {
            throw new IllegalArgumentException("不支持的文件格式，仅支持 .yaml/.yml/.json");
        }
    }
    
    // ===== 校验规则 =====
    
    private void validateOpenApiVersion(JSONObject dmi) {
        if (!dmi.has("openapi")) {
            error("缺少 openapi 字段");
            return;
        }
        
        String version = dmi.getString("openapi");
        if (!OPENAPI_VERSION_PATTERN.matcher(version).matches()) {
            error("openapi 版本必须为 3.0.x，当前: " + version);
        } else {
            System.out.println("✓ OpenAPI 版本: " + version);
        }
    }
    
    private void validateInfo(JSONObject dmi) {
        if (!dmi.has("info")) {
            error("缺少 info 字段");
            return;
        }
        
        JSONObject info = dmi.getJSONObject("info");
        
        // title
        if (!info.has("title") || info.getString("title").trim().isEmpty()) {
            error("info.title 不能为空");
        } else if (info.getString("title").length() > 128) {
            error("info.title 长度不能超过 128 字符");
        }
        
        // version
        if (!info.has("version")) {
            error("info.version 不能为空");
        } else if (!VERSION_PATTERN.matcher(info.getString("version")).matches()) {
            error("info.version 必须遵循 SemVer 格式 (如 1.2.3 或 1.2.3-beta)");
        }
        
        // x-ainative
        if (!info.has("x-ainative")) {
            error("info.x-ainative 不能为空");
            return;
        }
        
        JSONObject xAinative = info.getJSONObject("x-ainative");
        validateGlobalExtension(xAinative);
        
        System.out.println("✓ info 字段验证通过");
    }
    
    private void validateGlobalExtension(JSONObject xAinative) {
        // EXIST-001: assetType
        if (!xAinative.has("assetType") || !"dmi".equals(xAinative.getString("assetType"))) {
            error("EXIST-001: info.x-ainative.assetType 必须为 'dmi'");
        }
        
        // EXIST-002: publisher
        if (!xAinative.has("publisher")) {
            error("EXIST-002: info.x-ainative.publisher 不能为空");
        } else {
            String publisher = xAinative.getString("publisher");
            if (!PACKAGE_NAME_PATTERN.matcher(publisher).matches()) {
                error("EXIST-002: publisher 必须为有效包名格式 (如 com.example.app)");
            }
        }
        
        // category
        if (!xAinative.has("category")) {
            error("info.x-ainative.category 不能为空");
        } else if (!VALID_CATEGORIES.contains(xAinative.getString("category"))) {
            error("category 必须为: " + VALID_CATEGORIES);
        }
        
        // fallbackChain
        if (xAinative.has("fallbackChain")) {
            JSONArray chain = xAinative.getJSONArray("fallbackChain");
            for (int i = 0; i < chain.length(); i++) {
                String fallback = chain.getString(i);
                if (!FALLBACK_PATTERN.matcher(fallback).matches()) {
                    error("fallbackChain[" + i + "] 格式错误，应为 'type:assetId' (如 dmi:weather_v1)");
                }
            }
        }
        
        // minAndroidSdk
        if (xAinative.has("minAndroidSdk")) {
            int minSdk = xAinative.getInt("minAndroidSdk");
            if (minSdk < 29) {
                warning("minAndroidSdk < 29 可能不受支持");
            }
        }
    }
    
    private void validatePaths(JSONObject dmi) {
        if (!dmi.has("paths")) {
            error("缺少 paths 字段");
            return;
        }
        
        JSONObject paths = dmi.getJSONObject("paths");
        if (paths.length() == 0) {
            error("paths 至少需要一个操作");
            return;
        }
        
        Set<String> intentActionsSet = new HashSet<>();
        Set<String> operationIds = new HashSet<>();
        
        for (String path : paths.keySet()) {
            if (!path.startsWith("/")) {
                error("path 必须以 / 开头: " + path);
                continue;
            }
            
            JSONObject pathItem = paths.getJSONObject(path);
            for (String method : pathItem.keySet()) {
                if (!method.matches("^(get|post|put|delete|patch)$")) {
                    error("不支持的 HTTP 方法: " + method + " (path: " + path + ")");
                    continue;
                }
                
                JSONObject operation = pathItem.getJSONObject(method);
                validateOperation(operation, path, method, intentActionsSet, operationIds);
            }
        }
        
        System.out.println("✓ paths 字段验证通过");
    }
    
    private void validateOperation(JSONObject operation, String path, String method, 
                                   Set<String> intentActionsSet, Set<String> operationIds) {
        String operationPath = path + "." + method;
        
        // summary
        if (!operation.has("summary") || operation.getString("summary").trim().isEmpty()) {
            error(operationPath + ": summary 不能为空");
        } else if (operation.getString("summary").length() > 256) {
            error(operationPath + ": summary 长度不能超过 256 字符");
        }
        
        // operationId
        if (operation.has("operationId")) {
            String opId = operation.getString("operationId");
            if (!OPERATION_ID_PATTERN.matcher(opId).matches()) {
                error(operationPath + ": operationId 必须为有效标识符 (字母开头，字母数字下划线)");
            }
            if (operationIds.contains(opId)) {
                error(operationPath + ": operationId 重复: " + opId);
            }
            operationIds.add(opId);
        }
        
        // x-ainative
        if (!operation.has("x-ainative")) {
            error(operationPath + ": 缺少 x-ainative 字段");
            return;
        }
        JSONObject xAinative = operation.getJSONObject("x-ainative");
        validateOperationExtension(xAinative, operationPath, intentActionsSet);
        
        // execution
        if (!operation.has("execution")) {
            error(operationPath + ": 缺少 execution 字段");
            return;
        }
        JSONObject execution = operation.getJSONObject("execution");
        validateExecution(execution, operationPath, xAinative);
        
        // responses
        if (!operation.has("responses")) {
            warning(operationPath + ": 缺少 responses 字段");
        } else {
            JSONObject responses = operation.getJSONObject("responses");
            if (!responses.has("200")) {
                error(operationPath + ": responses 必须包含 200 响应");
            }
        }
    }
    
    private void validateOperationExtension(JSONObject xAinative, String operationPath, 
                                           Set<String> intentActionsSet) {
        // EXIST-003: intentActions
        if (!xAinative.has("intentActions")) {
            error("EXIST-003: " + operationPath + " 缺少 intentActions");
        } else {
            JSONArray intentActions = xAinative.getJSONArray("intentActions");
            if (intentActions.length() == 0) {
                error("EXIST-003: " + operationPath + " intentActions 至少需要一个");
            } else if (intentActions.length() > 10) {
                error(operationPath + ": intentActions 不能超过 10 个");
            }
            
            for (int i = 0; i < intentActions.length(); i++) {
                String action = intentActions.getString(i);
                if (action.trim().isEmpty() || action.length() > 64) {
                    error(operationPath + ": intentActions[" + i + "] 长度必须 1-64 字符");
                }
                if (intentActionsSet.contains(action)) {
                    warning(operationPath + ": intentAction 重复: '" + action + "'");
                }
                intentActionsSet.add(action);
            }
        }
        
        // EXIST-004: executorType
        if (!xAinative.has("executorType") || !"dmi".equals(xAinative.getString("executorType"))) {
            error("EXIST-004: " + operationPath + " executorType 必须为 'dmi'");
        }
        
        // EXIST-007: securityLevel
        if (!xAinative.has("securityLevel")) {
            error("EXIST-007: " + operationPath + " 缺少 securityLevel");
        } else {
            String securityLevel = xAinative.getString("securityLevel");
            if (!VALID_SECURITY_LEVELS.contains(securityLevel)) {
                error("EXIST-007: " + operationPath + " securityLevel 必须为 L0/L1/L2");
            }
            
            // EXIST-008: L2 必须声明 capabilityScope
            if ("L2".equals(securityLevel)) {
                if (!xAinative.has("capabilityScope")) {
                    error("EXIST-008: " + operationPath + " L2 级别必须声明 capabilityScope");
                } else {
                    JSONArray capabilityScope = xAinative.getJSONArray("capabilityScope");
                    if (capabilityScope.length() == 0) {
                        error("EXIST-008: " + operationPath + " capabilityScope 不能为空");
                    }
                    for (int i = 0; i < capabilityScope.length(); i++) {
                        String cap = capabilityScope.getString(i);
                        if (!CAPABILITY_PATTERN.matcher(cap).matches()) {
                            error(operationPath + ": capabilityScope[" + i + "] 格式错误，应为小写字母开头");
                        }
                    }
                }
                
                // EXIST-009: L2 必须声明 taintPolicy
                if (!xAinative.has("taintPolicy")) {
                    error("EXIST-009: " + operationPath + " L2 级别必须声明 taintPolicy");
                }
            }
        }
        
        // rateLimit
        if (xAinative.has("rateLimit")) {
            JSONObject rateLimit = xAinative.getJSONObject("rateLimit");
            if (!rateLimit.has("maxCallsPerMinute")) {
                error(operationPath + ": rateLimit 必须包含 maxCallsPerMinute");
            } else {
                int maxCalls = rateLimit.getInt("maxCallsPerMinute");
                if (maxCalls < 1 || maxCalls > 1000) {
                    error(operationPath + ": maxCallsPerMinute 必须在 1-1000 之间");
                }
            }
        }
        
        // timeout
        if (xAinative.has("timeout")) {
            int timeout = xAinative.getInt("timeout");
            if (timeout < 100 || timeout > 30000) {
                error(operationPath + ": timeout 必须在 100-30000 毫秒之间");
            }
        }
    }
    
    private void validateExecution(JSONObject execution, String operationPath, JSONObject xAinative) {
        if (!execution.has("bridge")) {
            error(operationPath + ": execution 必须包含 bridge");
            return;
        }
        
        JSONObject bridge = execution.getJSONObject("bridge");
        
        // EXIST-005: bridge.type
        if (!bridge.has("type")) {
            error("EXIST-005: " + operationPath + " bridge.type 不能为空");
        } else {
            String type = bridge.getString("type");
            if (!VALID_BRIDGE_TYPES.contains(type)) {
                error("EXIST-005: " + operationPath + " bridge.type 必须为: " + VALID_BRIDGE_TYPES);
            }
        }
        
        // EXIST-006: bridge.target
        if (!bridge.has("target") || bridge.getString("target").trim().isEmpty()) {
            error("EXIST-006: " + operationPath + " bridge.target 不能为空");
        }
        
        // 根据 bridge.type 校验 target 格式
        if (bridge.has("type") && bridge.has("target")) {
            String type = bridge.getString("type");
            String target = bridge.getString("target");
            
            switch (type) {
                case "http":
                    if (!target.startsWith("http://") && !target.startsWith("https://")) {
                        error(operationPath + ": http 桥接的 target 必须为 URL");
                    }
                    if (bridge.has("method")) {
                        String method = bridge.getString("method").toUpperCase();
                        if (!VALID_HTTP_METHODS.contains(method)) {
                            error(operationPath + ": HTTP method 必须为: " + VALID_HTTP_METHODS);
                        }
                    }
                    break;
                    
                case "app":
                case "intent":
                    if (!PACKAGE_NAME_PATTERN.matcher(target.split("/")[0]).matches()) {
                        warning(operationPath + ": app/intent 桥接的 target 应为包名或 ComponentName");
                    }
                    break;
                    
                case "system":
                    Set<String> validServices = new HashSet<>(Arrays.asList(
                        "notification", "calendar", "contacts", "media", "location", "telephony", "settings"
                    ));
                    if (!validServices.contains(target)) {
                        warning(operationPath + ": 系统服务 '" + target + "' 可能不受支持");
                    }
                    break;
            }
        }
    }
    
    private void validateExistenceRules(JSONObject dmi) {
        // 所有 EXIST-001~009 规则已在上述方法中内联检查
        System.out.println("✓ 存在性规则 EXIST-001~009 验证完成");
    }
    
    // ===== 工具方法 =====
    
    private void error(String message) {
        errorCount++;
        errors.add(message);
        System.err.println("✗ [ERROR] " + message);
    }
    
    private void warning(String message) {
        warningCount++;
        warnings.add(message);
        System.out.println("⚠ [WARNING] " + message);
    }
    
    private void printResults() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("校验结果");
        System.out.println("========================================");
        System.out.println("错误: " + errorCount);
        System.out.println("警告: " + warningCount);
        System.out.println();
        
        if (errorCount == 0) {
            System.out.println("✓ DMI 声明合规，可以注册");
        } else {
            System.err.println("✗ DMI 声明不合规，请修复错误后重试");
        }
    }
    
    private void generateReport(String reportPath, String inputFile) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath))) {
            writer.println("# DMI 校验报告");
            writer.println();
            writer.println("**文件**: `" + inputFile + "`  ");
            writer.println("**时间**: " + new java.util.Date() + "  ");
            writer.println("**Schema 版本**: " + SCHEMA_VERSION);
            writer.println();
            writer.println("## 摘要");
            writer.println();
            writer.println("| 项目 | 数量 |");
            writer.println("|------|------|");
            writer.println("| 错误 | " + errorCount + " |");
            writer.println("| 警告 | " + warningCount + " |");
            writer.println();
            
            if (errorCount > 0) {
                writer.println("## 错误详情");
                writer.println();
                for (int i = 0; i < errors.size(); i++) {
                    writer.println((i + 1) + ". " + errors.get(i));
                }
                writer.println();
            }
            
            if (warningCount > 0) {
                writer.println("## 警告详情");
                writer.println();
                for (int i = 0; i < warnings.size(); i++) {
                    writer.println((i + 1) + ". " + warnings.get(i));
                }
                writer.println();
            }
            
            if (errorCount == 0) {
                writer.println("## 结论");
                writer.println();
                writer.println("✅ **DMI 声明合规，可以注册**");
            } else {
                writer.println("## 结论");
                writer.println();
                writer.println("❌ **DMI 声明不合规，请修复错误**");
            }
            
            System.out.println("✓ 报告已生成: " + reportPath);
            
        } catch (IOException e) {
            System.err.println("✗ 报告生成失败: " + e.getMessage());
        }
    }
}
