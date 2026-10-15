package com.android.server.ainative;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * DSL 编译器 CI 工具（A7.2）
 * 
 * 用于 CI 流水线的命令行工具，编译并检查策略 DSL 规则集。
 * 
 * 使用方法:
 * java PolicyDslCiTool <policy-pack.json> <source-type>
 * 
 * source-type: platform | user | learning
 * 
 * 退出码:
 * 0 - 编译成功
 * 1 - 编译失败（有错误）
 * 2 - 参数错误
 */
public class PolicyDslCiTool {
    
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: PolicyDslCiTool <policy-pack.json> <source-type>");
            System.err.println("  source-type: platform | user | learning");
            System.exit(2);
        }
        
        String policyPackFile = args[0];
        String sourceType = args[1];
        
        // 验证 source-type
        if (!sourceType.equals("platform") && !sourceType.equals("user") && !sourceType.equals("learning")) {
            System.err.println("Error: Invalid source-type. Must be: platform | user | learning");
            System.exit(2);
        }
        
        // 读取文件
        String policyPackJson;
        try {
            policyPackJson = new String(Files.readAllBytes(Paths.get(policyPackFile)));
        } catch (IOException e) {
            System.err.println("Error: Failed to read file: " + policyPackFile);
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        
        // 编译
        PolicyDslCompiler compiler = new PolicyDslCompiler();
        PolicyDslCompiler.CompilationResult result = compiler.compile(policyPackJson, sourceType);
        
        // 输出报告
        System.out.println("=".repeat(80));
        System.out.println("Policy DSL Compilation Report");
        System.out.println("=".repeat(80));
        System.out.println("File: " + policyPackFile);
        System.out.println("Source Type: " + sourceType);
        System.out.println();
        
        if (result.success) {
            System.out.println("✓ COMPILATION SUCCESSFUL");
            System.out.println();
            
            if (!result.warnings.isEmpty()) {
                System.out.println("Warnings (" + result.warnings.size() + "):");
                for (String warning : result.warnings) {
                    System.out.println("  ⚠ " + warning);
                }
                System.out.println();
            }
            
            System.exit(0);
        } else {
            System.out.println("✗ COMPILATION FAILED");
            System.out.println();
            
            if (!result.errors.isEmpty()) {
                System.out.println("Errors (" + result.errors.size() + "):");
                for (String error : result.errors) {
                    System.out.println("  ✗ " + error);
                }
                System.out.println();
            }
            
            if (!result.conflicts.isEmpty()) {
                System.out.println("Conflicts (" + result.conflicts.size() + "):");
                for (PolicyDslCompiler.ConflictPair conflict : result.conflicts) {
                    System.out.println("  ⚠ " + conflict.toString());
                }
                System.out.println();
            }
            
            if (!result.warnings.isEmpty()) {
                System.out.println("Warnings (" + result.warnings.size() + "):");
                for (String warning : result.warnings) {
                    System.out.println("  ⚠ " + warning);
                }
                System.out.println();
            }
            
            System.exit(1);
        }
    }
}
