#!/usr/bin/env python3
"""
AI Native OS A1.1 任务验证脚本
验证平台工程基线是否正确设置
"""

import os
import sys
import json
from pathlib import Path
from typing import List, Tuple

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

class A1_1_Validator:
    def __init__(self, project_root: str):
        self.project_root = Path(project_root)
        self.errors = []
        self.warnings = []
        
    def check_manifests(self) -> bool:
        """检查 manifest 文件"""
        log_info("检查 manifest 文件...")
        
        manifest_file = self.project_root / "manifests" / "ainative.xml"
        
        if not manifest_file.exists():
            log_fail("manifests/ainative.xml 不存在")
            self.errors.append("Missing manifest file")
            return False
        
        # 检查 manifest 内容
        content = manifest_file.read_text(encoding='utf-8')
        
        required_elements = [
            '<remote name="aosp"',
            '<remote name="ainative"',
            '<default revision=',
            'platform/build',
            'platform/frameworks/base',
            'platform/system/core',
        ]
        
        for element in required_elements:
            if element not in content:
                log_fail(f"manifest 缺少必需元素: {element}")
                self.errors.append(f"Missing manifest element: {element}")
                return False
        
        log_success("manifest 文件检查通过")
        return True
    
    def check_overlay_structure(self) -> bool:
        """检查 overlay 目录结构"""
        log_info("检查 overlay 目录结构...")
        
        overlay_dir = self.project_root / "overlay"
        
        if not overlay_dir.exists():
            log_fail("overlay/ 目录不存在")
            self.errors.append("Missing overlay directory")
            return False
        
        # 检查 README
        if not (overlay_dir / "README.md").exists():
            log_warn("overlay/README.md 不存在")
            self.warnings.append("Missing overlay README")
        
        # 检查预期的子目录
        expected_dirs = [
            "frameworks",
            "packages", 
            "vendor"
        ]
        
        for dir_name in expected_dirs:
            dir_path = overlay_dir / dir_name
            if not dir_path.exists():
                log_warn(f"overlay/{dir_name}/ 目录不存在（将在后续任务创建）")
        
        log_success("overlay 目录结构检查通过")
        return True
    
    def check_scripts(self) -> bool:
        """检查构建脚本"""
        log_info("检查构建脚本...")
        
        scripts_dir = self.project_root / "scripts"
        
        required_scripts = [
            "sync.sh",
            "build.sh"
        ]
        
        all_exist = True
        for script in required_scripts:
            script_path = scripts_dir / script
            if not script_path.exists():
                log_fail(f"scripts/{script} 不存在")
                self.errors.append(f"Missing script: {script}")
                all_exist = False
            elif not os.access(script_path, os.X_OK):
                log_warn(f"scripts/{script} 不可执行")
                self.warnings.append(f"Script not executable: {script}")
        
        if all_exist:
            log_success("构建脚本检查通过")
        
        return all_exist
    
    def check_patches(self) -> bool:
        """检查补丁目录"""
        log_info("检查补丁目录...")
        
        patches_dir = self.project_root / "patches"
        
        if not patches_dir.exists():
            log_fail("patches/ 目录不存在")
            self.errors.append("Missing patches directory")
            return False
        
        # 检查 apply-patches.sh
        apply_script = patches_dir / "apply-patches.sh"
        if not apply_script.exists():
            log_fail("patches/apply-patches.sh 不存在")
            self.errors.append("Missing apply-patches.sh")
            return False
        
        # 检查 README
        if not (patches_dir / "README.md").exists():
            log_warn("patches/README.md 不存在")
            self.warnings.append("Missing patches README")
        
        log_success("补丁目录检查通过")
        return True
    
    def check_documentation(self) -> bool:
        """检查文档"""
        log_info("检查文档...")
        
        docs_dir = self.project_root / "docs"
        
        required_docs = [
            "BUILD.md",
            "DEVELOPMENT.md"
        ]
        
        all_exist = True
        for doc in required_docs:
            doc_path = docs_dir / doc
            if not doc_path.exists():
                log_fail(f"docs/{doc} 不存在")
                self.errors.append(f"Missing documentation: {doc}")
                all_exist = False
        
        if all_exist:
            log_success("文档检查通过")
        
        return all_exist
    
    def check_security_baseline(self) -> bool:
        """检查安全基线（E1.1 任务）"""
        log_info("检查 E1.1 安全基线...")
        
        # 检查安全检查工具
        security_tool = self.project_root / "tools" / "security_audit.py"
        if not security_tool.exists():
            log_warn("tools/security_audit.py 不存在（E1.1 任务未完成）")
            self.warnings.append("E1.1 security tool missing")
            return False
        
        # 检查安全红线文档
        security_doc = self.project_root / "docs" / "security" / "E1.1-安全红线检查单v1.md"
        if not security_doc.exists():
            log_warn("E1.1 安全红线检查单不存在")
            self.warnings.append("E1.1 security checklist missing")
            return False
        
        log_success("E1.1 安全基线检查通过")
        return True
    
    def generate_report(self) -> dict:
        """生成验证报告"""
        report = {
            "task": "A1.1 平台工程基线",
            "status": "PASS" if len(self.errors) == 0 else "FAIL",
            "errors": self.errors,
            "warnings": self.warnings,
            "checks": {
                "manifests": len([e for e in self.errors if "manifest" in e.lower()]) == 0,
                "overlay": len([e for e in self.errors if "overlay" in e.lower()]) == 0,
                "scripts": len([e for e in self.errors if "script" in e.lower()]) == 0,
                "patches": len([e for e in self.errors if "patch" in e.lower()]) == 0,
                "documentation": len([e for e in self.errors if "documentation" in e.lower()]) == 0
            }
        }
        return report
    
    def run_all_checks(self) -> bool:
        """运行所有检查"""
        print("\n" + "="*60)
        print("  A1.1 平台工程基线验证")
        print("="*60 + "\n")
        
        checks = [
            self.check_manifests,
            self.check_overlay_structure,
            self.check_scripts,
            self.check_patches,
            self.check_documentation,
            self.check_security_baseline
        ]
        
        results = []
        for check in checks:
            try:
                result = check()
                results.append(result)
            except Exception as e:
                log_error(f"检查失败: {e}")
                results.append(False)
            print()
        
        # 生成报告
        report = self.generate_report()
        
        print("="*60)
        print("  验证结果")
        print("="*60)
        print(f"状态: {report['status']}")
        print(f"错误: {len(report['errors'])}")
        print(f"警告: {len(report['warnings'])}")
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
        report_file = self.project_root / "reports" / "a1.1_validation.json"
        report_file.parent.mkdir(exist_ok=True)
        with open(report_file, 'w') as f:
            json.dump(report, f, indent=2)
        
        log_info(f"详细报告已保存: {report_file}")
        
        return len(report['errors']) == 0

def main():
    # 获取项目根目录
    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    
    validator = A1_1_Validator(str(project_root))
    success = validator.run_all_checks()
    
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
