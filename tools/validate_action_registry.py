#!/usr/bin/env python3
"""
AI Native OS - 动作注册表校验工具
版本：1.0.0
日期：2026-09-05

功能：
1. Schema 验证（JSON Schema）
2. 执行器存在性检查
3. 能力令牌映射校验
4. 工具名 1:1 映射检查
5. 冲突检测（同名动作、重复工具名）
6. 覆盖度报告
"""

import json
import sys
from pathlib import Path
from typing import Dict, List, Set, Tuple, Optional
from dataclasses import dataclass, field


@dataclass
class ValidationResult:
    """校验结果"""
    success: bool
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)
    stats: Dict[str, any] = field(default_factory=dict)


class ActionRegistryValidator:
    """动作注册表校验器"""
    
    # 已知能力令牌（应从能力注册表读取，此处硬编码示例）
    KNOWN_CAPABILITIES = {
        "agent.input_inject",
        "agent.launch_activity",
        "agent.query_packages",
        "agent.notification_action",
        "agent.device_control",
        "agent.payment_action",
        "agent.share_data",
    }
    
    # 已知审计事件类型（应从审计 schema 读取，此处硬编码示例）
    KNOWN_AUDIT_EVENT_TYPES = {
        "action.ui_tap",
        "action.ui_long_tap",
        "action.ui_swipe",
        "action.ui_type",
        "action.ui_key",
        "action.launch",
        "action.foreground",
        "action.query_apps",
        "action.query_intent",
        "action.notification_clear",
        "action.notification_reply",
        "action.device_volume",
        "action.device_brightness",
        "action.settings_open",
    }
    
    def __init__(self, registry_file: Path, schema_file: Optional[Path] = None):
        self.registry_file = registry_file
        self.schema_file = schema_file
        self.errors: List[str] = []
        self.warnings: List[str] = []
    
    def validate(self) -> ValidationResult:
        """执行校验"""
        print(f"[Action Registry Validator] 校验 {self.registry_file}")
        
        try:
            # 加载注册表
            registry = json.loads(self.registry_file.read_text(encoding="utf-8"))
            print(f"  ✓ 注册表加载成功")
            
            # Schema 验证
            if self.schema_file and self.schema_file.exists():
                self._validate_schema(registry)
            else:
                self.warnings.append("未提供 schema 文件，跳过 JSON Schema 验证")
            
            # 结构验证
            self._validate_structure(registry)
            
            # 执行器存在性检查
            self._validate_executors(registry)
            
            # 能力令牌映射检查
            self._validate_capabilities(registry)
            
            # 工具名 1:1 映射检查
            self._validate_tool_mapping(registry)
            
            # 冲突检测
            self._detect_conflicts(registry)
            
            # 覆盖度报告
            stats = self._calculate_coverage(registry)
            
            if self.errors:
                return ValidationResult(
                    success=False,
                    errors=self.errors,
                    warnings=self.warnings,
                    stats=stats,
                )
            
            return ValidationResult(
                success=True,
                warnings=self.warnings,
                stats=stats,
            )
        
        except Exception as e:
            self.errors.append(f"校验失败: {str(e)}")
            return ValidationResult(success=False, errors=self.errors)
    
    def _validate_schema(self, registry: Dict):
        """JSON Schema 验证（简化实现，生产应使用 jsonschema 库）"""
        # 检查顶层必填字段
        required_fields = ["version", "executors", "actions"]
        for field in required_fields:
            if field not in registry:
                self.errors.append(f"缺少必填字段: {field}")
        
        # 版本格式检查
        if "version" in registry:
            version = registry["version"]
            if not isinstance(version, str) or not version.count('.') == 2:
                self.errors.append(f"版本格式非法: {version}（需 X.Y.Z）")
    
    def _validate_structure(self, registry: Dict):
        """结构验证"""
        # 检查 executors 是否为数组
        if "executors" in registry:
            if not isinstance(registry["executors"], list):
                self.errors.append("executors 必须是数组")
            elif len(registry["executors"]) == 0:
                self.errors.append("executors 不能为空")
        
        # 检查 actions 是否为数组
        if "actions" in registry:
            if not isinstance(registry["actions"], list):
                self.errors.append("actions 必须是数组")
            elif len(registry["actions"]) == 0:
                self.errors.append("actions 不能为空")
    
    def _validate_executors(self, registry: Dict):
        """执行器存在性检查"""
        if "executors" not in registry or "actions" not in registry:
            return
        
        # 构建执行器 ID 集合
        executor_ids = set()
        for executor in registry["executors"]:
            if "id" not in executor:
                self.errors.append("执行器缺少 id 字段")
                continue
            
            executor_id = executor["id"]
            if executor_id in executor_ids:
                self.errors.append(f"执行器 ID 重复: {executor_id}")
            executor_ids.add(executor_id)
            
            # 检查 implementation 字段
            if "implementation" not in executor:
                self.errors.append(f"执行器 {executor_id} 缺少 implementation 字段")
        
        # 检查动作的 executorId 引用
        for action in registry["actions"]:
            if "name" not in action:
                self.errors.append("动作缺少 name 字段")
                continue
            
            action_name = action["name"]
            executor_id = action.get("executorId")
            
            if not executor_id:
                self.errors.append(f"动作 {action_name} 缺少 executorId")
            elif executor_id not in executor_ids:
                self.errors.append(f"动作 {action_name} 引用的执行器 {executor_id} 不存在")
    
    def _validate_capabilities(self, registry: Dict):
        """能力令牌映射校验"""
        if "actions" not in registry:
            return
        
        for action in registry["actions"]:
            action_name = action.get("name", "未命名")
            capability = action.get("requiredCapability")
            
            if not capability:
                self.errors.append(f"动作 {action_name} 缺少 requiredCapability")
            elif capability not in self.KNOWN_CAPABILITIES:
                self.warnings.append(f"动作 {action_name} 引用未知能力 {capability}")
            
            # 检查审计事件类型
            audit_event = action.get("auditEventType")
            if not audit_event:
                self.errors.append(f"动作 {action_name} 缺少 auditEventType")
            elif audit_event not in self.KNOWN_AUDIT_EVENT_TYPES:
                self.warnings.append(f"动作 {action_name} 引用未知审计事件类型 {audit_event}")
    
    def _validate_tool_mapping(self, registry: Dict):
        """工具名 1:1 映射检查（关键：openphone 集成仓纪律）"""
        if "actions" not in registry:
            return
        
        tool_names = set()
        action_names = set()
        
        for action in registry["actions"]:
            action_name = action.get("name", "未命名")
            
            # 检查动作名唯一性
            if action_name in action_names:
                self.errors.append(f"动作名重复: {action_name}")
            action_names.add(action_name)
            
            # 检查工具名映射
            meta = action.get("_meta", {})
            tool_name = meta.get("modelToolName")
            
            if not tool_name:
                self.errors.append(f"动作 {action_name} 缺少 _meta.modelToolName（1:1 映射要求）")
            else:
                if tool_name in tool_names:
                    self.errors.append(f"工具名重复: {tool_name}（违反 1:1 映射）")
                tool_names.add(tool_name)
                
                # 检查 toolSchemaMatch
                if not meta.get("toolSchemaMatch"):
                    self.warnings.append(f"动作 {action_name} 的工具 schema 未校验匹配")
    
    def _detect_conflicts(self, registry: Dict):
        """冲突检测"""
        if "actions" not in registry:
            return
        
        # 检查同名前缀冲突（例如 ui.tap 和 ui.tap_long）
        action_names = [action.get("name", "") for action in registry["actions"]]
        
        for i, name1 in enumerate(action_names):
            for name2 in action_names[i+1:]:
                if name1.startswith(name2) or name2.startswith(name1):
                    self.warnings.append(f"动作名前缀冲突: {name1} ↔ {name2}")
    
    def _calculate_coverage(self, registry: Dict) -> Dict[str, any]:
        """覆盖度报告"""
        stats = {
            "total_executors": 0,
            "total_actions": 0,
            "risk_distribution": {"low": 0, "medium": 0, "high": 0},
            "executor_distribution": {},
            "capabilities_used": set(),
            "tool_mapping_complete": True,
        }
        
        if "executors" in registry:
            stats["total_executors"] = len(registry["executors"])
        
        if "actions" in registry:
            stats["total_actions"] = len(registry["actions"])
            
            for action in registry["actions"]:
                # 风险分布
                risk_level = action.get("riskLevel", -1)
                if risk_level == 0:
                    stats["risk_distribution"]["low"] += 1
                elif risk_level == 1:
                    stats["risk_distribution"]["medium"] += 1
                elif risk_level == 2:
                    stats["risk_distribution"]["high"] += 1
                
                # 执行器分布
                executor_id = action.get("executorId", "未知")
                stats["executor_distribution"][executor_id] = \
                    stats["executor_distribution"].get(executor_id, 0) + 1
                
                # 能力使用
                capability = action.get("requiredCapability")
                if capability:
                    stats["capabilities_used"].add(capability)
                
                # 工具映射完整性
                meta = action.get("_meta", {})
                if not meta.get("modelToolName"):
                    stats["tool_mapping_complete"] = False
        
        # 转换 set 为 list（JSON 序列化）
        stats["capabilities_used"] = list(stats["capabilities_used"])
        
        return stats


def main():
    """命令行入口"""
    if len(sys.argv) < 2:
        print("用法: python validate_action_registry.py <registry_file> [schema_file]")
        print("示例: python validate_action_registry.py ../vendor/genton/config/action_registry.json")
        sys.exit(1)
    
    registry_file = Path(sys.argv[1])
    schema_file = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    
    if not registry_file.exists():
        print(f"错误: 注册表文件不存在 {registry_file}")
        sys.exit(1)
    
    if schema_file and not schema_file.exists():
        print(f"警告: Schema 文件不存在 {schema_file}")
        schema_file = None
    
    validator = ActionRegistryValidator(registry_file, schema_file)
    result = validator.validate()
    
    print("\n" + "="*60)
    print("校验结果")
    print("="*60)
    
    if result.success:
        print(f"✓ 校验通过")
        
        print(f"\n统计信息:")
        for key, value in result.stats.items():
            if isinstance(value, dict):
                print(f"  {key}:")
                for k, v in value.items():
                    print(f"    {k}: {v}")
            elif isinstance(value, list):
                print(f"  {key}: {', '.join(value)}")
            else:
                print(f"  {key}: {value}")
        
        if result.warnings:
            print(f"\n⚠ 警告 ({len(result.warnings)}):")
            for warning in result.warnings:
                print(f"  - {warning}")
    else:
        print(f"✗ 校验失败")
        print(f"\n错误 ({len(result.errors)}):")
        for error in result.errors:
            print(f"  - {error}")
        
        if result.warnings:
            print(f"\n⚠ 警告 ({len(result.warnings)}):")
            for warning in result.warnings:
                print(f"  - {warning}")
        
        sys.exit(1)
    
    print("\n" + "="*60)


if __name__ == "__main__":
    main()
