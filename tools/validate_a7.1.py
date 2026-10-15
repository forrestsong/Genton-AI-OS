#!/usr/bin/env python3
"""
A7.1 策略引擎验证工具

验证 A7.1 任务的交付物是否完整。

验证项：
1. AIDL 接口文件存在性
2. PolicyEngineService 实现
3. PolicyRuleEvaluator 实现
4. 测试工具存在性
5. 文档完整性
"""

import os
import sys

def check_file_exists(path, description):
    """检查文件是否存在"""
    if os.path.exists(path):
        print(f"✓ {description}: {path}")
        return True
    else:
        print(f"✗ {description}: {path} NOT FOUND")
        return False

def count_lines(path):
    """统计文件行数"""
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return len(f.readlines())
    except:
        return 0

def main():
    print("=" * 60)
    print("A7.1 策略引擎验证工具")
    print("=" * 60)
    
    base_path = r"d:\AI Native OS"
    passed = 0
    failed = 0
    
    # 1. AIDL 接口文件
    print("\n[1] AIDL 接口文件")
    aidl_files = [
        ("overlay/frameworks/base/core/java/com/ainative/policy/IPolicyEngine.aidl", "IPolicyEngine"),
        ("overlay/frameworks/base/core/java/com/ainative/policy/IntentEvaluationRequest.aidl", "IntentEvaluationRequest"),
        ("overlay/frameworks/base/core/java/com/ainative/policy/CapabilityRequest.aidl", "CapabilityRequest"),
        ("overlay/frameworks/base/core/java/com/ainative/policy/PolicyDecision.aidl", "PolicyDecision"),
        ("overlay/frameworks/base/core/java/com/ainative/policy/TaintMapEntry.aidl", "TaintMapEntry"),
        ("overlay/frameworks/base/core/java/com/ainative/policy/PolicyRuleView.aidl", "PolicyRuleView"),
        ("overlay/frameworks/base/core/java/com/ainative/policy/PolicyStats.aidl", "PolicyStats"),
        ("overlay/frameworks/base/core/java/com/ainative/policy/IPolicyListener.aidl", "IPolicyListener"),
    ]
    
    for rel_path, desc in aidl_files:
        full_path = os.path.join(base_path, rel_path)
        if check_file_exists(full_path, desc):
            passed += 1
        else:
            failed += 1
    
    # 2. PolicyEngineService
    print("\n[2] PolicyEngineService 实现")
    service_path = os.path.join(base_path, "overlay/frameworks/base/services/core/java/com/android/server/ainative/PolicyEngineService.java")
    if check_file_exists(service_path, "PolicyEngineService"):
        lines = count_lines(service_path)
        print(f"   代码行数: {lines} 行")
        if lines >= 600:
            print(f"   ✓ 代码量符合预期（>= 600 行）")
            passed += 1
        else:
            print(f"   ✗ 代码量不足（< 600 行）")
            failed += 1
    else:
        failed += 1
    
    # 3. PolicyRuleEvaluator
    print("\n[3] PolicyRuleEvaluator 实现")
    evaluator_path = os.path.join(base_path, "overlay/frameworks/base/services/core/java/com/android/server/ainative/PolicyRuleEvaluator.java")
    if check_file_exists(evaluator_path, "PolicyRuleEvaluator"):
        lines = count_lines(evaluator_path)
        print(f"   代码行数: {lines} 行")
        if lines >= 300:
            print(f"   ✓ 代码量符合预期（>= 300 行）")
            passed += 1
        else:
            print(f"   ✗ 代码量不足（< 300 行）")
            failed += 1
    else:
        failed += 1
    
    # 4. 测试工具
    print("\n[4] 测试工具")
    test_path = os.path.join(base_path, "tools/test_policy_engine.py")
    if check_file_exists(test_path, "test_policy_engine.py"):
        passed += 1
    else:
        failed += 1
    
    # 5. 文档
    print("\n[5] 文档")
    docs = [
        ("reports/A7.1_完成报告.md", "完成报告"),
        ("docs/A7.1_快速参考.md", "快速参考"),
    ]
    
    for rel_path, desc in docs:
        full_path = os.path.join(base_path, rel_path)
        if check_file_exists(full_path, desc):
            passed += 1
        else:
            failed += 1
    
    # 汇总
    print("\n" + "=" * 60)
    print(f"验证结果: {passed} / {passed + failed} 通过")
    print("=" * 60)
    
    if failed == 0:
        print("✓ A7.1 策略引擎验证通过")
        return 0
    else:
        print(f"✗ A7.1 策略引擎验证失败（{failed} 项未通过）")
        return 1

if __name__ == "__main__":
    sys.exit(main())
