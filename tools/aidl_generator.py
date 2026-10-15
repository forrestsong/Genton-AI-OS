#!/usr/bin/env python3
"""
AI Native OS AIDL 骨架生成器

根据轨道 A 接口草案文档自动生成 AIDL 接口文件、Android.bp 构建配置和 API 表面文件。

使用方法:
    python tools/aidl_generator.py --interface IAgentUiTreeService \\
        --package com.genton.perception \\
        --output overlay/frameworks/base/core/java/com/genton/perception/
"""

import os
import sys
import argparse
import json
from pathlib import Path
from typing import List, Dict, Optional
from dataclasses import dataclass

# AIDL 接口定义（从轨道 A 接口清单提取）
AIDL_INTERFACES = {
    # 感知层 (7 个)
    "perception": [
        {
            "name": "IAgentUiTreeService",
            "methods": [
                "UiSnapshot captureUiTree(in UiTreeRequest request)",
                "void subscribeUiChanges(in IAgentUiTreeListener listener)",
                "void unsubscribeUiChanges(in IAgentUiTreeListener listener)",
            ],
            "imports": ["UiSnapshot", "UiTreeRequest", "IAgentUiTreeListener"],
        },
        {
            "name": "IAgentScreenService",
            "methods": [
                "ScreenFrame captureScreen(in CaptureRequest request)",
                "ScreenStreamHandle startScreenStream(in ScreenStreamConfig config, in IAgentFrameListener listener)",
                "void stopScreenStream(in ScreenStreamHandle handle)",
            ],
            "imports": ["ScreenFrame", "CaptureRequest", "ScreenStreamConfig", "ScreenStreamHandle", "IAgentFrameListener"],
        },
        {
            "name": "IAgentNotificationService",
            "methods": [
                "List<NotificationEnvelope> queryNotifications(in NotificationQuery query)",
                "void dismissNotification(String key)",
                "void performNotificationAction(String key, in NotificationAction action)",
            ],
            "imports": ["NotificationEnvelope", "NotificationQuery", "NotificationAction"],
        },
    ],
    
    # 执行层 (2 个)
    "action": [
        {
            "name": "IAgentActionService",
            "methods": [
                "void executeAction(in ActionRequest request, in IAgentActionCallback callback)",
                "ActionExecutionStatus queryActionStatus(String actionId)",
                "void cancelAction(String actionId)",
            ],
            "imports": ["ActionRequest", "ActionExecutionStatus", "IAgentActionCallback"],
        },
        {
            "name": "IAgentQueryService",
            "methods": [
                "List<AppInfo> queryInstalledApps()",
                "ActionInfo resolveIntent(in ComponentTarget target)",
            ],
            "imports": ["AppInfo", "ActionInfo", "ComponentTarget"],
        },
    ],
    
    # Agent 运行时 (1 个)
    "agent": [
        {
            "name": "IAgentRuntimeService",
            "methods": [
                "AgentHandle registerAgent(in AgentRegistration registration, in IAgentRuntimeListener listener)",
                "void unregisterAgent(in AgentHandle handle)",
                "AgentRuntimeStatus getAgentStatus(in AgentHandle handle)",
                "CapabilityToken requestCapability(in AgentHandle handle, String capability)",
                "void checkpoint(in AgentHandle handle)",
            ],
            "imports": ["AgentHandle", "AgentRegistration", "IAgentRuntimeListener", "AgentRuntimeStatus", "CapabilityToken"],
        },
    ],
    
    # 记忆服务 (1 个)
    "memory": [
        {
            "name": "IAgentMemoryService",
            "methods": [
                "void store(in AgentHandle agent, in MemoryEntryDraft draft)",
                "List<MemoryEntry> query(in AgentHandle agent, in MemoryQuery query)",
                "void forget(in AgentHandle agent, in ForgetRequest request)",
            ],
            "imports": ["AgentHandle", "MemoryEntryDraft", "MemoryEntry", "MemoryQuery", "ForgetRequest"],
        },
    ],
    
    # 审计服务 (1 个)
    "audit": [
        {
            "name": "IAgentAuditService",
            "methods": [
                "void logEvent(in AuditEvent event)",
                "List<AuditEvent> queryEvents(in AuditQuery query)",
                "ChainStatus verifyChain()",
            ],
            "imports": ["AuditEvent", "AuditQuery", "ChainStatus"],
        },
    ],
    
    # 上下文服务 (1 个)
    "context": [
        {
            "name": "IAgentContextService",
            "methods": [
                "void subscribeContext(in AgentHandle agent, in IAgentContextListener listener)",
                "void unsubscribeContext(in AgentHandle agent)",
            ],
            "imports": ["AgentHandle", "IAgentContextListener"],
        },
    ],
    
    # 策略引擎 (1 个)
    "policy": [
        {
            "name": "IPolicyEngine",
            "methods": [
                "boolean evaluatePolicy(in AgentHandle agent, String actionId)",
                "CapabilityToken issueToken(in AgentHandle agent, String capability)",
                "void revokeToken(in CapabilityToken token)",
            ],
            "imports": ["AgentHandle", "CapabilityToken"],
        },
    ],
    
    # 事件流 (1 个)
    "events": [
        {
            "name": "IAgentEventStreamService",
            "methods": [
                "void subscribe(in EventSubscription subscription, in IAgentEventSink sink)",
                "void unsubscribe(String subscriptionId)",
            ],
            "imports": ["EventSubscription", "IAgentEventSink"],
        },
    ],
}

@dataclass
class AIDLSpec:
    """AIDL 接口规范"""
    name: str
    package: str
    methods: List[str]
    imports: List[str]
    
class AIDLGenerator:
    def __init__(self, output_dir: str, project_root: str):
        self.output_dir = Path(output_dir)
        self.project_root = Path(project_root)
        
    def generate_aidl_file(self, spec: AIDLSpec) -> str:
        """生成 AIDL 接口文件"""
        lines = []
        
        # 包声明
        lines.append(f"package {spec.package};")
        lines.append("")
        
        # 导入声明
        if spec.imports:
            for imp in spec.imports:
                lines.append(f"import {spec.package}.{imp};")
            lines.append("")
        
        # 接口定义
        lines.append("/**")
        lines.append(f" * {spec.name}")
        lines.append(" * AI Native OS 系统服务接口")
        lines.append(" * 自动生成 - 请勿手动编辑")
        lines.append(" */")
        lines.append(f"interface {spec.name} {{")
        lines.append("")
        
        # 方法定义
        for method in spec.methods:
            lines.append(f"    {method};")
            lines.append("")
        
        lines.append("}")
        
        return "\n".join(lines)
    
    def generate_parcelable(self, name: str, package: str) -> str:
        """生成 Parcelable 占位符"""
        lines = []
        lines.append(f"package {package};")
        lines.append("")
        lines.append("/**")
        lines.append(f" * {name} Parcelable")
        lines.append(" * 自动生成骨架 - 需要根据草案补全字段")
        lines.append(" */")
        lines.append(f"parcelable {name};")
        return "\n".join(lines)
    
    def generate_callback_interface(self, name: str, package: str) -> str:
        """生成回调接口占位符"""
        lines = []
        lines.append(f"package {package};")
        lines.append("")
        lines.append("/**")
        lines.append(f" * {name}")
        lines.append(" * 回调接口 - 需要根据草案补全方法")
        lines.append(" */")
        lines.append(f"interface {name} {{")
        lines.append("    // TODO: 添加回调方法")
        lines.append("}")
        return "\n".join(lines)
    
    def generate_android_bp(self, package: str, interfaces: List[str]) -> str:
        """生成 Android.bp 构建配置"""
        module_name = package.replace("com.genton.", "genton.")
        
        lines = []
        lines.append("// AI Native OS AIDL 模块")
        lines.append("// 自动生成")
        lines.append("")
        lines.append("aidl_interface {")
        lines.append(f'    name: "{module_name}",')
        lines.append("    srcs: [")
        
        for interface in interfaces:
            lines.append(f'        "{interface}.aidl",')
        
        lines.append("    ],")
        lines.append("    local_include_dir: \".\",")
        lines.append("    stability: \"vintf\",")
        lines.append("    backend: {")
        lines.append("        java: {")
        lines.append("            sdk_version: \"system_current\",")
        lines.append("            platform_apis: true,")
        lines.append("        },")
        lines.append("        cpp: {")
        lines.append("            enabled: false,")
        lines.append("        },")
        lines.append("        ndk: {")
        lines.append("            enabled: false,")
        lines.append("        },")
        lines.append("    },")
        lines.append("    versions: [\"1\"],")
        lines.append("}")
        
        return "\n".join(lines)
    
    def generate_api_current(self, package: str, interfaces: List[str]) -> str:
        """生成 API 表面文件"""
        lines = []
        for interface in interfaces:
            lines.append(f"{package}.{interface}")
        return "\n".join(lines)
    
    def generate_module(self, module_name: str):
        """生成完整模块"""
        if module_name not in AIDL_INTERFACES:
            print(f"❌ 未知模块: {module_name}")
            print(f"可用模块: {', '.join(AIDL_INTERFACES.keys())}")
            return False
        
        interfaces = AIDL_INTERFACES[module_name]
        package = f"com.genton.{module_name}"
        
        # 创建输出目录
        module_dir = self.output_dir / module_name
        module_dir.mkdir(parents=True, exist_ok=True)
        
        print(f"\n🔧 生成模块: {module_name}")
        print(f"📦 包名: {package}")
        print(f"📂 输出目录: {module_dir}")
        print()
        
        all_interfaces = []
        all_parcelables = set()
        all_callbacks = set()
        
        # 生成接口文件
        for interface_spec in interfaces:
            name = interface_spec["name"]
            all_interfaces.append(name)
            
            spec = AIDLSpec(
                name=name,
                package=package,
                methods=interface_spec["methods"],
                imports=interface_spec["imports"]
            )
            
            # 生成 AIDL 文件
            aidl_content = self.generate_aidl_file(spec)
            aidl_file = module_dir / f"{name}.aidl"
            aidl_file.write_text(aidl_content, encoding="utf-8")
            print(f"  ✅ {aidl_file.name}")
            
            # 收集 Parcelable 和回调接口
            for imp in interface_spec["imports"]:
                if imp.startswith("I") and "Listener" in imp or "Callback" in imp or "Sink" in imp:
                    all_callbacks.add(imp)
                else:
                    all_parcelables.add(imp)
        
        # 生成 Parcelable 占位符
        if all_parcelables:
            print()
            print("  📝 生成 Parcelable 占位符:")
            for parcelable in sorted(all_parcelables):
                content = self.generate_parcelable(parcelable, package)
                file = module_dir / f"{parcelable}.aidl"
                file.write_text(content, encoding="utf-8")
                print(f"    ✅ {file.name}")
        
        # 生成回调接口占位符
        if all_callbacks:
            print()
            print("  📞 生成回调接口占位符:")
            for callback in sorted(all_callbacks):
                content = self.generate_callback_interface(callback, package)
                file = module_dir / f"{callback}.aidl"
                file.write_text(content, encoding="utf-8")
                print(f"    ✅ {file.name}")
        
        # 生成 Android.bp
        print()
        bp_content = self.generate_android_bp(package, all_interfaces + list(all_parcelables) + list(all_callbacks))
        bp_file = module_dir / "Android.bp"
        bp_file.write_text(bp_content, encoding="utf-8")
        print(f"  ✅ Android.bp")
        
        # 生成 API 表面
        api_dir = module_dir / "api"
        api_dir.mkdir(exist_ok=True)
        api_content = self.generate_api_current(package, all_interfaces)
        api_file = api_dir / "current.txt"
        api_file.write_text(api_content, encoding="utf-8")
        print(f"  ✅ api/current.txt")
        
        # 生成 README
        readme_content = f"""# {package}

AI Native OS {module_name} 层 AIDL 接口

## 接口清单

"""
        for interface in all_interfaces:
            readme_content += f"- `{interface}`: {module_name} 系统服务\n"
        
        readme_content += "\n## 构建\n\n```bash\nm genton.{module_name}\n```\n"
        
        readme_file = module_dir / "README.md"
        readme_file.write_text(readme_content, encoding="utf-8")
        print(f"  ✅ README.md")
        
        print()
        print(f"✅ 模块 {module_name} 生成完成！")
        print(f"   生成了 {len(all_interfaces)} 个接口, {len(all_parcelables)} 个 Parcelable, {len(all_callbacks)} 个回调")
        
        return True
    
    def generate_all(self):
        """生成所有模块"""
        print("="*60)
        print("  AI Native OS AIDL 骨架生成器")
        print("="*60)
        
        success_count = 0
        for module_name in AIDL_INTERFACES.keys():
            if self.generate_module(module_name):
                success_count += 1
        
        print()
        print("="*60)
        print(f"✅ 完成！成功生成 {success_count}/{len(AIDL_INTERFACES)} 个模块")
        print("="*60)
        print()
        print("下一步:")
        print("  1. 根据轨道 A 草案补全 Parcelable 字段")
        print("  2. 补全回调接口方法")
        print("  3. 运行构建验证: ./scripts/build.sh --module <module>")

def main():
    parser = argparse.ArgumentParser(description="AI Native OS AIDL 骨架生成器")
    parser.add_argument("--module", help="生成指定模块 (perception/action/agent/...)")
    parser.add_argument("--all", action="store_true", help="生成所有模块")
    parser.add_argument("--output", default="overlay/frameworks/base/core/java/com/genton",
                       help="输出目录 (默认: overlay/frameworks/base/core/java/com/genton)")
    parser.add_argument("--list", action="store_true", help="列出所有可用模块")
    
    args = parser.parse_args()
    
    # 获取项目根目录
    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    output_dir = project_root / args.output
    
    generator = AIDLGenerator(str(output_dir), str(project_root))
    
    if args.list:
        print("可用模块:")
        for module in AIDL_INTERFACES.keys():
            interfaces = AIDL_INTERFACES[module]
            print(f"  - {module}: {len(interfaces)} 个接口")
        return
    
    if args.all:
        generator.generate_all()
    elif args.module:
        generator.generate_module(args.module)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
