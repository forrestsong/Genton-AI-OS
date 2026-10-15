#!/usr/bin/env python3
"""
安全基线自动化检查工具（E1.1）

用法:
    python tools/security_audit.py --checklist E1.1
    python tools/security_audit.py --all
    python tools/security_audit.py --report html

依赖: 无（纯 Python 3.7+）
"""

import os
import re
import json
import sys
import argparse
from pathlib import Path
from typing import List, Dict, Any
from datetime import datetime


class SecurityViolation:
    """安全违规记录"""
    def __init__(self, rule: str, severity: str, message: str, locations: List[str] = None):
        self.rule = rule
        self.severity = severity  # CRITICAL, HIGH, MEDIUM, LOW
        self.message = message
        self.locations = locations or []
        self.timestamp = datetime.now()


class SecurityAuditor:
    """安全基线审计器"""
    
    SEVERITY_ICONS = {
        "CRITICAL": "🔴",
        "HIGH": "🟠",
        "MEDIUM": "🟡",
        "LOW": "🔵",
        "INFO": "⚪"
    }
    
    def __init__(self, base_dir: str):
        self.base_dir = Path(base_dir)
        self.violations: List[SecurityViolation] = []
        self.checks_run = 0
        self.checks_passed = 0
    
    def add_violation(self, rule: str, severity: str, message: str, locations: List[str] = None):
        """添加违规项"""
        self.violations.append(SecurityViolation(rule, severity, message, locations))
    
    # ========== 1. 权限最小化检查 ==========
    
    def check_permission_protection_level(self):
        """检查权限保护级别（规则 1.1）"""
        self.checks_run += 1
        print("  检查 1.1: 系统服务权限保护级别...")
        
        manifest_path = self.base_dir / "overlay/frameworks/base/core/res/AndroidManifest.xml"
        if not manifest_path.exists():
            self.add_violation(
                "1.1 系统服务权限",
                "INFO",
                f"AndroidManifest.xml 尚未创建: {manifest_path}",
                [str(manifest_path)]
            )
            return
        
        with open(manifest_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # 检查 dangerous 权限（不应出现）
        dangerous_pattern = r'<permission[^>]*android:name="([^"]*com\.genton[^"]*)"[^>]*android:protectionLevel="dangerous"'
        dangerous_perms = re.findall(dangerous_pattern, content)
        
        if dangerous_perms:
            self.add_violation(
                "1.1 系统服务权限",
                "HIGH",
                f"发现 {len(dangerous_perms)} 个 dangerous 权限（应使用 signature|privileged）",
                dangerous_perms[:5]
            )
        else:
            self.checks_passed += 1
            print("    ✅ 无 dangerous 权限")
        
        # 检查是否所有 com.genton.* 权限都是 signature|privileged
        genton_perms = re.findall(
            r'<permission[^>]*android:name="(com\.genton[^"]*)"[^>]*android:protectionLevel="([^"]*)"',
            content
        )
        
        for perm_name, prot_level in genton_perms:
            if "signature" not in prot_level:
                self.add_violation(
                    "1.1 系统服务权限",
                    "HIGH",
                    f"权限 {perm_name} 保护级别为 {prot_level}（应包含 signature）",
                    [perm_name]
                )
    
    def check_capability_registry(self):
        """检查能力注册表（规则 1.2）"""
        self.checks_run += 1
        print("  检查 1.2: 能力粒度控制...")
        
        registry_path = self.base_dir / "overlay/vendor/genton/config/action_registry.json"
        if not registry_path.exists():
            self.add_violation(
                "1.2 能力粒度控制",
                "INFO",
                f"动作注册表尚未创建: {registry_path}",
                [str(registry_path)]
            )
            return
        
        with open(registry_path, 'r', encoding='utf-8') as f:
            registry = json.load(f)
        
        actions = registry.get("actions", [])
        if not actions:
            self.add_violation(
                "1.2 能力粒度控制",
                "MEDIUM",
                "动作注册表为空",
                [str(registry_path)]
            )
            return
        
        # 检查每个动作是否映射到 capability
        missing_capability = []
        for action in actions:
            if not action.get("requiredCapability"):
                missing_capability.append(action.get("name", "unknown"))
        
        if missing_capability:
            self.add_violation(
                "1.2 能力粒度控制",
                "HIGH",
                f"{len(missing_capability)} 个动作未映射 capability",
                missing_capability[:5]
            )
        else:
            self.checks_passed += 1
            print(f"    ✅ 所有 {len(actions)} 个动作已映射 capability")
        
        # 检查敏感动作是否标记为 high 风险
        sensitive_actions = {
            "messages.send": "high",
            "calls.place": "high",
            "share.content": "high",
            "calendar.delete": "high",
            "account.access": "high"
        }
        
        for action in actions:
            expected_risk = sensitive_actions.get(action.get("name"))
            if expected_risk and action.get("riskLevel") != expected_risk:
                self.add_violation(
                    "1.2 能力粒度控制",
                    "MEDIUM",
                    f"敏感动作 {action['name']} 风险等级为 {action.get('riskLevel')}（应为 {expected_risk}）",
                    [action["name"]]
                )
    
    # ========== 2. Deny 默认检查 ==========
    
    def check_policy_default_deny(self):
        """检查策略引擎 deny 默认（规则 2.1）"""
        self.checks_run += 1
        print("  检查 2.1: 策略引擎 deny 默认...")
        
        policy_paths = [
            self.base_dir / "overlay/packages/apps/GentonBrain/policy/PolicyEngine.java",
            self.base_dir / "overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/policy/PolicyEngine.java"
        ]
        
        policy_file = None
        for path in policy_paths:
            if path.exists():
                policy_file = path
                break
        
        if not policy_file:
            self.add_violation(
                "2.1 策略引擎",
                "INFO",
                "策略引擎文件尚未创建",
                [str(p) for p in policy_paths]
            )
            return
        
        with open(policy_file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # 检查是否有默认 DENY 逻辑
        has_default_deny = (
            "return PolicyDecision.DENY" in content or
            "return DENY" in content or
            "default: return DENY" in content
        )
        
        if not has_default_deny:
            self.add_violation(
                "2.1 策略引擎",
                "CRITICAL",
                "策略引擎缺少默认 DENY 逻辑（无匹配规则时必须拒绝）",
                [str(policy_file)]
            )
        else:
            self.checks_passed += 1
            print("    ✅ 策略默认 deny 逻辑存在")
    
    def check_token_fail_secure(self):
        """检查令牌 fail-secure（规则 2.2）"""
        self.checks_run += 1
        print("  检查 2.2: 令牌 fail-secure...")
        
        token_paths = [
            self.base_dir / "overlay/frameworks/base/services/core/java/com/android/server/genton/TokenValidator.java",
            self.base_dir / "overlay/packages/apps/GentonBrain/token/TokenValidator.java"
        ]
        
        token_file = None
        for path in token_paths:
            if path.exists():
                token_file = path
                break
        
        if not token_file:
            self.add_violation(
                "2.2 令牌 fail-secure",
                "INFO",
                "令牌验证器尚未创建",
                [str(p) for p in token_paths]
            )
            return
        
        with open(token_file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # 检查签名失败处理
        if "catch" in content and "return true" in content:
            # 可能在异常时返回 true（不安全）
            self.add_violation(
                "2.2 令牌 fail-secure",
                "CRITICAL",
                "令牌验证器可能在异常时返回通过（应 fail-secure 拒绝）",
                [str(token_file)]
            )
        else:
            self.checks_passed += 1
            print("    ✅ 令牌验证逻辑看起来安全")
    
    # ========== 3. Taint 纪律检查 ==========
    
    def check_taint_annotation(self):
        """检查 taint 来源标注（规则 3.1）"""
        self.checks_run += 1
        print("  检查 3.1: Taint 来源标注...")
        
        perception_paths = [
            self.base_dir / "overlay/frameworks/base/core/java/com/genton/perception",
            self.base_dir / "overlay/frameworks/base/services/core/java/com/android/server/genton/perception"
        ]
        
        found_trustlevel = False
        for path in perception_paths:
            if path.exists():
                for java_file in path.rglob("*.java"):
                    with open(java_file, 'r', encoding='utf-8') as f:
                        if "TrustLevel" in f.read():
                            found_trustlevel = True
                            break
            if found_trustlevel:
                break
        
        if not found_trustlevel:
            self.add_violation(
                "3.1 Taint 来源标注",
                "INFO",
                "感知服务尚未实现 TrustLevel 标注",
                [str(p) for p in perception_paths]
            )
        else:
            self.checks_passed += 1
            print("    ✅ 发现 TrustLevel 类型定义")
    
    # ========== 4. Secret 安全路径检查 ==========
    
    def check_secret_handling(self):
        """检查 secret 值安全处理（规则 4.1）"""
        self.checks_run += 1
        print("  检查 4.1: Secret 安全路径...")
        
        # 检查是否在日志/审计中泄露 secret
        audit_paths = [
            self.base_dir / "overlay/frameworks/base/services/core/java/com/android/server/openphone/AuditLog.java",
            self.base_dir / "overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/policy/AuditLog.java"
        ]
        
        for audit_file in audit_paths:
            if not audit_file.exists():
                continue
            
            with open(audit_file, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # 检查是否有 secret 过滤逻辑
            has_secret_filter = (
                "secret" in content.lower() and
                ("redact" in content.lower() or "mask" in content.lower() or "hasValue" in content)
            )
            
            if not has_secret_filter:
                self.add_violation(
                    "4.1 Secret 安全路径",
                    "HIGH",
                    f"审计日志 {audit_file.name} 可能记录 secret 值（应过滤）",
                    [str(audit_file)]
                )
            else:
                self.checks_passed += 1
                print("    ✅ 审计日志包含 secret 过滤逻辑")
            break
    
    # ========== 5. 确认宿主独占检查 ==========
    
    def check_confirmation_exclusive(self):
        """检查确认宿主独占（规则 5.1）"""
        self.checks_run += 1
        print("  检查 5.1: 确认宿主独占...")
        
        systemui_paths = [
            self.base_dir / "overlay/frameworks/base/packages/SystemUI",
            self.base_dir / "overlay/packages/apps/SystemUI"
        ]
        
        found_confirmation = False
        for systemui_dir in systemui_paths:
            if systemui_dir.exists():
                for java_file in systemui_dir.rglob("*Confirmation*.java"):
                    found_confirmation = True
                    with open(java_file, 'r', encoding='utf-8') as f:
                        content = f.read()
                    
                    # 检查是否有 FLAG_SECURE
                    if "FLAG_SECURE" not in content:
                        self.add_violation(
                            "5.1 确认宿主独占",
                            "HIGH",
                            f"确认面板 {java_file.name} 未设置 FLAG_SECURE（必需防截图）",
                            [str(java_file)]
                        )
                    else:
                        self.checks_passed += 1
                        print("    ✅ 确认面板设置了 FLAG_SECURE")
                    break
            if found_confirmation:
                break
        
        if not found_confirmation:
            self.add_violation(
                "5.1 确认宿主独占",
                "INFO",
                "确认面板尚未创建",
                [str(p) for p in systemui_paths]
            )
    
    # ========== 6. FLAG_SECURE 边界检查 ==========
    
    def check_secure_content_protection(self):
        """检查 FLAG_SECURE 边界（规则 6.1）"""
        self.checks_run += 1
        print("  检查 6.1: FLAG_SECURE 保护...")
        
        screen_service_paths = [
            self.base_dir / "overlay/frameworks/base/services/core/java/com/android/server/genton/AgentScreenService.java",
            self.base_dir / "overlay/frameworks/base/services/core/java/com/android/server/openphone/OpenPhoneAgentManagerService.java"
        ]
        
        for service_file in screen_service_paths:
            if not service_file.exists():
                continue
            
            with open(service_file, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # 检查是否检测 FLAG_SECURE
            if "FLAG_SECURE" in content and ("secureRegions" in content or "STATUS_SECURE_CONTENT" in content):
                self.checks_passed += 1
                print("    ✅ 屏幕服务包含 FLAG_SECURE 检测")
            else:
                self.add_violation(
                    "6.1 FLAG_SECURE 保护",
                    "HIGH",
                    f"屏幕服务 {service_file.name} 未检测 FLAG_SECURE（必需防泄露）",
                    [str(service_file)]
                )
            break
    
    # ========== 7. 审计完整性检查 ==========
    
    def check_audit_integrity(self):
        """检查审计防篡改（规则 7.1）"""
        self.checks_run += 1
        print("  检查 7.1: 审计防篡改...")
        
        audit_paths = [
            self.base_dir / "overlay/frameworks/base/services/core/java/com/android/server/openphone/AuditLog.java",
            self.base_dir / "overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/policy/AuditLog.java"
        ]
        
        for audit_file in audit_paths:
            if not audit_file.exists():
                continue
            
            with open(audit_file, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # 检查哈希链
            has_hash_chain = (
                "prevHash" in content or "prev_hash" in content
            ) and (
                "SHA-256" in content or "sha256" in content.lower()
            )
            
            if not has_hash_chain:
                self.add_violation(
                    "7.1 审计防篡改",
                    "CRITICAL",
                    f"审计日志 {audit_file.name} 未实现哈希链（必需防篡改）",
                    [str(audit_file)]
                )
            else:
                self.checks_passed += 1
                print("    ✅ 审计日志包含哈希链逻辑")
            
            # 检查是否禁止 delete
            if "delete" in content.lower() and "audit" in content.lower():
                self.add_violation(
                    "7.1 审计防篡改",
                    "HIGH",
                    f"审计日志 {audit_file.name} 可能支持删除操作（应只增不改）",
                    [str(audit_file)]
                )
            
            break
    
    # ========== 报告生成 ==========
    
    def generate_console_report(self) -> bool:
        """生成控制台报告"""
        print("\n" + "=" * 80)
        print(f"安全基线检查报告 - {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        print("=" * 80)
        
        if not self.violations:
            print(f"\n🎉 所有 {self.checks_run} 项检查通过！")
            return True
        
        # 按严重性分组
        by_severity = {}
        for v in self.violations:
            by_severity.setdefault(v.severity, []).append(v)
        
        # 统计
        critical_count = len(by_severity.get("CRITICAL", []))
        high_count = len(by_severity.get("HIGH", []))
        medium_count = len(by_severity.get("MEDIUM", []))
        low_count = len(by_severity.get("LOW", []))
        info_count = len(by_severity.get("INFO", []))
        
        print(f"\n检查结果: {self.checks_passed}/{self.checks_run} 通过")
        print(f"违规项: {len(self.violations)} 个")
        if critical_count:
            print(f"  🔴 严重: {critical_count}")
        if high_count:
            print(f"  🟠 高: {high_count}")
        if medium_count:
            print(f"  🟡 中: {medium_count}")
        if low_count:
            print(f"  🔵 低: {low_count}")
        if info_count:
            print(f"  ⚪ 信息: {info_count}")
        
        print("\n详细违规项:\n")
        
        for severity in ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"]:
            violations = by_severity.get(severity, [])
            if not violations:
                continue
            
            for v in violations:
                icon = self.SEVERITY_ICONS.get(severity, "⚪")
                print(f"{icon} [{severity}] {v.rule}")
                print(f"   {v.message}")
                if v.locations:
                    locations_str = ", ".join(v.locations[:3])
                    if len(v.locations) > 3:
                        locations_str += f" ... (+{len(v.locations) - 3} 更多)"
                    print(f"   位置: {locations_str}")
                print()
        
        # 判断是否阻塞
        is_blocking = critical_count > 0 or high_count > 0
        if is_blocking:
            print("⚠️  发现阻塞性问题，需修复后才能通过 A1 门禁")
        
        return not is_blocking
    
    def generate_html_report(self, output_path: str):
        """生成 HTML 报告"""
        html = f"""
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>安全基线检查报告 - E1.1</title>
    <style>
        body {{ font-family: 'Segoe UI', Arial, sans-serif; margin: 20px; background: #f5f5f5; }}
        .container {{ max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }}
        h1 {{ color: #333; border-bottom: 3px solid #1976d2; padding-bottom: 10px; }}
        .summary {{ display: flex; gap: 20px; margin: 20px 0; }}
        .stat-card {{ flex: 1; padding: 20px; border-radius: 6px; text-align: center; }}
        .stat-card.passed {{ background: #e8f5e9; border-left: 4px solid #4caf50; }}
        .stat-card.failed {{ background: #ffebee; border-left: 4px solid #f44336; }}
        .stat-number {{ font-size: 48px; font-weight: bold; margin: 10px 0; }}
        .violation {{ margin: 15px 0; padding: 15px; border-left: 4px solid; border-radius: 4px; }}
        .violation.CRITICAL {{ background: #ffebee; border-color: #d32f2f; }}
        .violation.HIGH {{ background: #fff3e0; border-color: #f57c00; }}
        .violation.MEDIUM {{ background: #fffde7; border-color: #fbc02d; }}
        .violation.LOW {{ background: #e3f2fd; border-color: #1976d2; }}
        .violation.INFO {{ background: #f5f5f5; border-color: #9e9e9e; }}
        .violation-header {{ font-weight: bold; font-size: 16px; margin-bottom: 8px; }}
        .violation-message {{ margin: 5px 0; color: #555; }}
        .violation-locations {{ font-size: 12px; color: #777; font-family: monospace; }}
        .timestamp {{ color: #888; font-size: 14px; }}
    </style>
</head>
<body>
    <div class="container">
        <h1>🔒 安全基线检查报告（E1.1）</h1>
        <p class="timestamp">生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
        
        <div class="summary">
            <div class="stat-card passed">
                <div>通过检查</div>
                <div class="stat-number">{self.checks_passed}</div>
                <div>{self.checks_run} 项检查</div>
            </div>
            <div class="stat-card failed">
                <div>违规项</div>
                <div class="stat-number">{len(self.violations)}</div>
                <div>需要修复</div>
            </div>
        </div>
        
        <h2>违规详情</h2>
"""
        
        if not self.violations:
            html += "<p>🎉 未发现违规项，所有检查通过！</p>"
        else:
            for v in self.violations:
                locations_html = ""
                if v.locations:
                    locations_html = f"<div class='violation-locations'>位置: {', '.join(v.locations[:5])}</div>"
                
                html += f"""
        <div class="violation {v.severity}">
            <div class="violation-header">{self.SEVERITY_ICONS.get(v.severity)} [{v.severity}] {v.rule}</div>
            <div class="violation-message">{v.message}</div>
            {locations_html}
        </div>
"""
        
        html += """
    </div>
</body>
</html>
"""
        
        with open(output_path, 'w', encoding='utf-8') as f:
            f.write(html)
        
        print(f"\n📄 HTML 报告已生成: {output_path}")
    
    def run_all_checks(self):
        """运行所有检查"""
        print("\n🔍 开始安全基线检查（E1.1）...\n")
        
        self.check_permission_protection_level()
        self.check_capability_registry()
        self.check_policy_default_deny()
        self.check_token_fail_secure()
        self.check_taint_annotation()
        self.check_secret_handling()
        self.check_confirmation_exclusive()
        self.check_secure_content_protection()
        self.check_audit_integrity()


def main():
    parser = argparse.ArgumentParser(description="安全基线自动化检查工具（E1.1）")
    parser.add_argument("--checklist", default="E1.1", help="检查清单版本（默认 E1.1）")
    parser.add_argument("--all", action="store_true", help="运行所有检查")
    parser.add_argument("--report", choices=["console", "html"], default="console", help="报告格式")
    parser.add_argument("--output", default="reports/security_audit.html", help="HTML 报告输出路径")
    
    args = parser.parse_args()
    
    # 获取项目根目录
    base_dir = Path(__file__).parent.parent
    
    # 创建审计器
    auditor = SecurityAuditor(base_dir)
    
    # 运行检查
    auditor.run_all_checks()
    
    # 生成报告
    if args.report == "html":
        output_path = base_dir / args.output
        output_path.parent.mkdir(parents=True, exist_ok=True)
        auditor.generate_html_report(str(output_path))
    
    success = auditor.generate_console_report()
    
    # 返回退出码
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
