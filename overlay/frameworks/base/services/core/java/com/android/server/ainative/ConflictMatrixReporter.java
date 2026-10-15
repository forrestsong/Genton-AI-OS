package com.android.server.ainative;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * 冲突矩阵报告生成器（A7.2）
 * 
 * 生成策略规则冲突矩阵的详细报告，用于 CI 和人工审查。
 */
public class ConflictMatrixReporter {
    
    /**
     * 生成冲突矩阵报告
     * 
     * @param policyPackJson PolicyPack JSON 字符串
     * @param sourceType 来源类型
     * @param outputFile 输出文件路径
     * @return 是否成功
     */
    public boolean generateReport(String policyPackJson, String sourceType, String outputFile) {
        PolicyDslCompiler compiler = new PolicyDslCompiler();
        PolicyDslCompiler.CompilationResult result = compiler.compile(policyPackJson, sourceType);
        
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write("# Policy DSL Conflict Matrix Report\n\n");
            writer.write("**Source Type**: " + sourceType + "\n");
            writer.write("**Generated**: " + new Date() + "\n\n");
            
            writer.write("---\n\n");
            
            // 编译状态
            if (result.success) {
                writer.write("## Compilation Status: ✓ SUCCESS\n\n");
            } else {
                writer.write("## Compilation Status: ✗ FAILED\n\n");
            }
            
            // 错误列表
            if (!result.errors.isEmpty()) {
                writer.write("## Errors (" + result.errors.size() + ")\n\n");
                for (int i = 0; i < result.errors.size(); i++) {
                    writer.write(String.format("%d. %s\n", i + 1, result.errors.get(i)));
                }
                writer.write("\n");
            }
            
            // 冲突矩阵
            if (!result.conflicts.isEmpty()) {
                writer.write("## Conflict Matrix (" + result.conflicts.size() + " conflicts)\n\n");
                writer.write("| Rule 1 | Rule 2 | Reason |\n");
                writer.write("|--------|--------|--------|\n");
                
                for (PolicyDslCompiler.ConflictPair conflict : result.conflicts) {
                    writer.write(String.format("| %s | %s | %s |\n", 
                                               conflict.ruleId1, 
                                               conflict.ruleId2, 
                                               conflict.reason));
                }
                writer.write("\n");
            } else {
                writer.write("## Conflict Matrix\n\n");
                writer.write("✓ No conflicts detected.\n\n");
            }
            
            // 告警列表
            if (!result.warnings.isEmpty()) {
                writer.write("## Warnings (" + result.warnings.size() + ")\n\n");
                for (int i = 0; i < result.warnings.size(); i++) {
                    writer.write(String.format("%d. %s\n", i + 1, result.warnings.get(i)));
                }
                writer.write("\n");
            }
            
            // 规则统计
            writer.write("## Rule Statistics\n\n");
            Map<String, Integer> stats = computeStatistics(policyPackJson);
            writer.write("- **Total Rules**: " + stats.getOrDefault("total", 0) + "\n");
            writer.write("- **Baseline Rules**: " + stats.getOrDefault("baseline", 0) + "\n");
            writer.write("- **Action Domain**: " + stats.getOrDefault("action", 0) + "\n");
            writer.write("- **Notification Domain**: " + stats.getOrDefault("notification", 0) + "\n");
            writer.write("- **Call Gate Domain**: " + stats.getOrDefault("call_gate", 0) + "\n");
            writer.write("- **Learn Rules**: " + stats.getOrDefault("learn", 0) + "\n");
            writer.write("\n");
            
            // 验收结论
            writer.write("---\n\n");
            writer.write("## Verification Conclusion\n\n");
            
            if (result.success && result.conflicts.isEmpty()) {
                writer.write("✓ **PASS**: Policy pack is valid and conflict-free.\n");
            } else if (result.success && !result.conflicts.isEmpty()) {
                writer.write("⚠ **PASS WITH WARNINGS**: Policy pack compiled but has warnings.\n");
            } else {
                writer.write("✗ **FAIL**: Policy pack contains errors and cannot be deployed.\n");
            }
            
            return result.success;
            
        } catch (IOException e) {
            System.err.println("Failed to write report: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 计算规则统计信息
     */
    private Map<String, Integer> computeStatistics(String policyPackJson) {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("total", 0);
        stats.put("baseline", 0);
        stats.put("action", 0);
        stats.put("notification", 0);
        stats.put("call_gate", 0);
        stats.put("learn", 0);
        
        try {
            JSONObject policyPack = new JSONObject(policyPackJson);
            if (!policyPack.has("rules")) {
                return stats;
            }
            
            JSONArray rules = policyPack.getJSONArray("rules");
            stats.put("total", rules.length());
            
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                
                // Baseline 统计
                if (rule.optBoolean("baseline", false)) {
                    stats.put("baseline", stats.get("baseline") + 1);
                }
                
                // 域统计
                String domain = rule.optString("domain", "action");
                stats.put(domain, stats.getOrDefault(domain, 0) + 1);
                
                // Learn 规则统计
                String id = rule.optString("id", "");
                if (id.startsWith("learn.")) {
                    stats.put("learn", stats.get("learn") + 1);
                }
            }
            
        } catch (JSONException e) {
            // 解析失败，返回空统计
        }
        
        return stats;
    }
    
    /**
     * CLI 入口
     */
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: ConflictMatrixReporter <policy-pack.json> <source-type> <output-report.md>");
            System.exit(2);
        }
        
        String policyPackFile = args[0];
        String sourceType = args[1];
        String outputFile = args[2];
        
        try {
            String policyPackJson = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(policyPackFile)));
            
            ConflictMatrixReporter reporter = new ConflictMatrixReporter();
            boolean success = reporter.generateReport(policyPackJson, sourceType, outputFile);
            
            System.out.println("Report generated: " + outputFile);
            System.exit(success ? 0 : 1);
            
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }
}
