#!/usr/bin/env python3
"""
AI Native OS A1.3 任务验证脚本
验证 AIDL 骨架生成是否正确完成
"""

import os
import sys
from pathlib import Path
from typing import List, Dict

# 颜色输出
class Colors:
    GREEN = '\033[0;32m'
    RED = '\033[0;31m'
    YELLOW = '\033[1;33m'
    BLUE = '\033[0;34m'
    NC = '\033[0m'

def log_info(msg: str):
    print(f"{Colors.GREEN}[INFO]{Colors.NC} {msg}")

def log_warn(msg: str):
    print(f"{Colors.YELLOW}[WARN]{Colors.NC} {msg}")

def log_error(msg: str):
    print(f"{Colors.RED}[ERROR]{Colors.NC} {msg}")

def log_success(msg: str):
    print(f"{Colors.GREEN}[✓]{Colors.NC} {msg}")

def log_fail(msg: str):
    print(f"{Colors.RED}[✗]{Colors.NC} {msg}")

# 预期的模块和接口
EXPECTED_MODULES = {
    "perception": {
        "interfaces": ["IAgentUiTreeService", "IAgentScreenService", "IAgentNotificationService"],
        "description": "感知层（UI树/屏幕/通知）"
    },
    "action": {
        "interfaces": ["IAgentActionService", "IAgentQueryService"],
        "description": "执行层（动作执行/查询）"
    },
    "agent": {
        "interfaces": ["IAgentRuntimeService"],
        "description": "Agent 运行时（ACB）"
    },
    "memory": {
        "interfaces": ["IAgentMemoryService"],
        "description": "记忆服务"
    },
    "audit": {
        "interfaces": ["IAgentAuditService"],
        "description": "审计服务"
    },
    "context": {
        "interfaces": ["IAgentContextService"],
        "description": "上下文服务"
    },
    "policy": {
        "interfaces": ["IPolicyEngine"],
        "description": "策略引擎"
    },
    "events": {
        "interfaces": ["IAgentEventStreamService"],
        "description": "事件流"
    },
}

class A1_3_Validator:
    def __init__(self, project_root: str):
        self.project_root = Path(project_root)
        self.aidl_base = self.project_root / "overlay" / "frameworks" / "base" / "core" / "java" / "com" / "genton"
        self.errors = []
        self.warnings = []
        self.stats = {
            "modules": 0,
            "interfaces": 0,
            "parcelables": 0,
            "callbacks": 0,
            "android_bp": 0,
            "api_files": 0
        }
        
    def check_module(self, module_name: str, expected: Dict) -> bool:
        """检查单个模块"""
        log_info(f"检查模块: {module_name} - {expected['description']}")
        
        module_dir = self.aidl_base / module_name
        
        if not module_dir.exists():
            log_fail(f"模块目录不存在: {module_dir}")
            self.errors.append(f"Missing module directory: {module_name}")
            return False
        
        module_ok = True
        
        # 检查接口文件
        for interface in expected["interfaces"]:
            aidl_file = module_dir / f"{interface}.aidl"
            if aidl_file.exists():
                log_success(f"  {interface}.aidl")
                self.stats["interfaces"] += 1
            else:
                log_fail(f"  {interface}.aidl 不存在")
                self.errors.append(f"Missing interface: {interface}")
                module_ok = False
        
        # 检查 Android.bp
        bp_file = module_dir / "Android.bp"
        if bp_file.exists():
            log_success(f"  Android.bp")
            self.stats["android_bp"] += 1
            
            # 检查内容
            content = bp_file.read_text(encoding='utf-8')
            if f'name: "genton.{module_name}"' not in content:
                log_warn(f"  Android.bp 模块名称可能不正确")
                self.warnings.append(f"Android.bp name mismatch in {module_name}")
        else:
            log_fail(f"  Android.bp 不存在")
            self.errors.append(f"Missing Android.bp in {module_name}")
            module_ok = False
        
        # 检查 API 文件
        api_file = module_dir / "api" / "current.txt"
        if api_file.exists():
            log_success(f"  api/current.txt")
            self.stats["api_files"] += 1
        else:
            log_fail(f"  api/current.txt 不存在")
            self.errors.append(f"Missing api/current.txt in {module_name}")
            module_ok = False
        
        # 统计 Parcelable 和回调
        for file in module_dir.glob("*.aidl"):
            content = file.read_text(encoding='utf-8')
            if "parcelable" in content.lower():
                self.stats["parcelables"] += 1
            elif file.stem.startswith("I") and ("Listener" in file.stem or "Callback" in file.stem or "Sink" in file.stem):
                self.stats["callbacks"] += 1
        
        if module_ok:
            self.stats["modules"] += 1
        
        print()
        return module_ok
    
    def check_aidl_generator(self) -> bool:
        """检查 AIDL 生成器工具"""
        log_info("检查 AIDL 生成器工具...")
        
        generator_file = self.project_root / "tools" / "aidl_generator.py"
        
        if not generator_file.exists():
            log_fail("tools/aidl_generator.py 不存在")
            self.errors.append("Missing AIDL generator tool")
            return False
        
        # 检查文件大小（应该有实质内容）
        size = generator_file.stat().st_size
        if size < 5000:
            log_warn(f"AIDL 生成器文件较小 ({size} bytes)，可能不完整")
            self.warnings.append("AIDL generator may be incomplete")
        
        log_success("AIDL 生成器工具存在")
        return True
    
    def generate_report(self) -> dict:
        """生成验证报告"""
        report = {
            "task": "A1.3 AIDL 骨架生成",
            "status": "PASS" if len(self.errors) == 0 else "FAIL",
            "errors": self.errors,
            "warnings": self.warnings,
            "statistics": self.stats,
            "expected_modules": len(EXPECTED_MODULES),
            "checks": {
                "aidl_generator": len([e for e in self.errors if "generator" in e.lower()]) == 0,
                "all_modules": self.stats["modules"] == len(EXPECTED_MODULES),
                "build_configs": self.stats["android_bp"] == len(EXPECTED_MODULES),
                "api_files": self.stats["api_files"] == len(EXPECTED_MODULES)
            }
        }
        return report
    
    def run_all_checks(self) -> bool:
        """运行所有检查"""
        print("\n" + "="*60)
        print("  A1.3 AIDL 骨架生成验证")
        print("="*60 + "\n")
        
        # 检查 AIDL 生成器
        self.check_aidl_generator()
        print()
        
        # 检查所有模块
        for module_name, expected in EXPECTED_MODULES.items():
            self.check_module(module_name, expected)
        
        # 生成报告
        report = self.generate_report()
        
        print("="*60)
        print("  验证结果")
        print("="*60)
        print(f"状态: {report['status']}")
        print(f"错误: {len(report['errors'])}")
        print(f"警告: {len(report['warnings'])}")
        print()
        
        print("统计:")
        print(f"  模块: {self.stats['modules']}/{len(EXPECTED_MODULES)}")
        print(f"  接口: {self.stats['interfaces']}")
        print(f"  Parcelable: {self.stats['parcelables']}")
        print(f"  回调接口: {self.stats['callbacks']}")
        print(f"  Android.bp: {self.stats['android_bp']}/{len(EXPECTED_MODULES)}")
        print(f"  API 文件: {self.stats['api_files']}/{len(EXPECTED_MODULES)}")
        print()
        
        if report['errors']:
            print(f"{Colors.RED}错误列表:{Colors.NC}")
            for error in report['errors']:
                print(f"  - {error}")
            print()
        
        if report['warnings']:
            print(f"{Colors.YELLOW}警告列表:{Colors.NC}")
            for warning in report['warnings']:
                print(f"  - {warning}")
            print()
        
        # 保存报告
        import json
        report_file = self.project_root / "reports" / "a1.3_validation.json"
        report_file.parent.mkdir(exist_ok=True)
        with open(report_file, 'w', encoding='utf-8') as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        
        log_info(f"详细报告已保存: {report_file}")
        
        return len(report['errors']) == 0

def main():
    # 获取项目根目录
    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    
    validator = A1_3_Validator(str(project_root))
    success = validator.run_all_checks()
    
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
