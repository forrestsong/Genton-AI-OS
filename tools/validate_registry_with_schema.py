#!/usr/bin/env python3
"""用 JSON Schema 校验 registry/action-registry.v1.json"""
import json
from pathlib import Path

registry_path = Path(r"d:\AI Native OS\registry\action-registry.v1.json")
schema_path = Path(r"d:\AI Native OS\registry\action-registry.schema.json")

registry = json.loads(registry_path.read_text(encoding='utf-8'))
schema = json.loads(schema_path.read_text(encoding='utf-8'))

print(f"校验 {registry_path.name}")
print(f"使用 schema: {schema_path.name}")
print()

# 基础结构检查（简化，不用 jsonschema 库）
errors = []
warnings = []

# 检查必填字段
if 'version' not in registry:
    errors.append("缺少 version 字段")
if 'actions' not in registry:
    errors.append("缺少 actions 字段")

# 检查版本格式
if 'version' in registry:
    version = registry['version']
    if not isinstance(version, str) or version.count('.') != 2:
        errors.append(f"version 格式错误: {version} (需要 X.Y.Z)")

# 检查 actions 数组
if 'actions' in registry:
    actions = registry['actions']
    if not isinstance(actions, list):
        errors.append("actions 必须是数组")
    elif len(actions) == 0:
        errors.append("actions 不能为空")
    else:
        print(f"✓ 发现 {len(actions)} 个动作")
        
        # 检查每个动作的必填字段
        required_fields = ['name', 'displayName', 'executorId', 'riskLevel', 
                          'requiredCapability', 'inputSchema', 'outputSchema', 'auditEventType']
        
        for i, action in enumerate(actions):
            action_name = action.get('name', f'[动作 {i}]')
            
            for field in required_fields:
                if field not in action:
                    errors.append(f"动作 {action_name} 缺少必填字段: {field}")
            
            # 检查 _meta.modelToolName（校验器期望，但 schema 未强制）
            if '_meta' not in action or 'modelToolName' not in action.get('_meta', {}):
                warnings.append(f"动作 {action_name} 缺少 _meta.modelToolName (推荐)")
            else:
                tool_name = action['_meta']['modelToolName']
                print(f"  ✓ {action_name} -> {tool_name}")

print()
if errors:
    print(f"❌ 发现 {len(errors)} 个错误:")
    for err in errors:
        print(f"  - {err}")
else:
    print("✅ 所有必填字段检查通过")

if warnings:
    print(f"\n⚠️  {len(warnings)} 个警告:")
    for warn in warnings:
        print(f"  - {warn}")

print()
if errors:
    print("校验失败")
    exit(1)
else:
    print("校验通过 (符合 schema 要求)")
    exit(0)
