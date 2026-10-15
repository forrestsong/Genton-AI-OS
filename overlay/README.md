# AI Native OS Overlay

此目录包含对 AOSP 上游代码的覆盖和扩展，采用 overlay 机制实现增量定制。

## 目录结构

```
overlay/
├── frameworks/
│   └── base/
│       └── core/java/com/ainative/
│           ├── perception/          # 感知层 AIDL（7个接口）
│           ├── action/              # 执行层 AIDL（5个接口）
│           ├── agent/               # Agent 运行时 AIDL（3个接口）
│           ├── memory/              # 记忆服务 AIDL（2个接口）
│           ├── audit/               # 审计引擎 AIDL（2个接口）
│           ├── semantics/           # 语义层 AIDL（3个接口）
│           ├── events/              # 事件流 AIDL（2个接口）
│           ├── context/             # 上下文服务 AIDL（3个接口）
│           ├── policy/              # 策略引擎 AIDL（3个接口）
│           ├── assets/              # 资产管理 AIDL（2个接口）
│           ├── ui/                  # UI 组件 AIDL（2个接口）
│           ├── inference/           # 推理路由 AIDL（2个接口）
│           ├── sandbox/             # 沙箱服务 AIDL（2个接口）
│           └── call/                # 通话门 AIDL（2个接口）
├── packages/
│   └── apps/
│       ├── AINativeBrain/          # 大脑特权 App
│       ├── Settings/                # Settings 扩展
│       └── SystemUI/                # SystemUI 扩展
└── vendor/
    └── ainative/
        ├── config/
        │   ├── action_registry.json        # 动作注册表
        │   ├── capability_definitions.json # 能力定义
        │   ├── policy_baseline.dsl         # 基线策略
        │   └── privapp-permissions-ainative.xml
        ├── products/
        │   ├── ainative_common.mk          # 通用产品配置
        │   └── ainative_generic_arm64.mk   # Generic 产品
        └── sepolicy/
            ├── private/                     # 私有 SELinux 策略
            └── public/                      # 公开 SELinux 策略
```

## 工作原理

1. **编译时覆盖**：Soong 构建系统优先使用 overlay/ 中的文件
2. **增量扩展**：仅覆盖需要修改的文件，其余使用 AOSP 原版
3. **版本追踪**：与 AOSP 上游保持同步，便于后续升级

## 开发规范

### 添加新的 AIDL 接口

1. 在对应目录创建 `.aidl` 文件
2. 创建 `Android.bp` 配置 aidl_interface
3. 更新 `api/current.txt` 记录 API 表面
4. 运行 `tools/aidl_generator.py` 自动生成模板

示例：
```bash
python tools/aidl_generator.py \
  --interface IAgentUiTreeService \
  --package com.ainative.perception \
  --output overlay/frameworks/base/core/java/com/ainative/perception/
```

### 修改系统服务

1. 在 `frameworks/base/services/core/java/com/android/server/` 下创建服务
2. 在 `SystemServer.java` 中注册服务
3. 配置 SELinux 策略允许访问
4. 添加权限声明到 `AndroidManifest.xml`

## 相关文档

- [AIDL 接口清单](../docs/轨道A-接口全景清单.md)
- [构建指南](../docs/BUILD.md)
- [A1.3 AIDL 骨架生成任务](../A1里程碑实施计划.md)
