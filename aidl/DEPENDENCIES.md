# AI Native OS - AIDL 模块依赖关系

## 依赖层级图

```
Layer 0 (无依赖基础模块):
├── audit                # 审计服务 (独立)
└── perception           # 感知服务 (仅依赖 Android 框架)

Layer 1 (依赖 Layer 0):
├── agent                # Agent 运行时
├── policy               # 策略引擎
└── memory               # 记忆服务

Layer 2 (依赖 Layer 1):
├── events               # 事件流 (依赖 agent, policy)
├── action               # 动作执行 (依赖 policy)
├── semantics            # 语义服务 (依赖 memory)
└── context              # 上下文服务 (依赖 perception, events, agent)

Layer 3 (依赖 Layer 2):
├── assets               # 资产目录 (依赖 action, policy)
├── inference            # 推理路由 (依赖 agent, policy, audit)
└── sandbox              # 沙箱执行 (依赖 assets, policy)

Layer 4 (依赖 Layer 3):
├── ui                   # UI 契约 (依赖 action, agent, context)
└── call                 # 通话门控 (依赖 agent, policy, ui)

Layer 5 (特殊模块):
└── motion               # 姿态交互 (依赖 events, policy)
```

## 依赖矩阵

| 模块 | 依赖 | 被依赖 |
|------|------|--------|
| **perception** | - | context |
| **audit** | - | inference |
| **agent** | policy, action | events, memory, context, inference, ui, call |
| **policy** | agent | action, events, assets, inference, sandbox, ui, call, motion |
| **memory** | agent | semantics |
| **events** | agent, policy | context, motion |
| **action** | policy | assets, ui |
| **semantics** | memory | - |
| **context** | perception, events, agent | ui |
| **assets** | action, policy | sandbox |
| **inference** | agent, policy, audit | - |
| **sandbox** | assets, policy | - |
| **ui** | action, agent, context | call |
| **call** | agent, policy, ui | - |
| **motion** | events, policy | - |

## 编译顺序

**阶段 1**: 基础模块
```bash
m com.genton.audit
m com.genton.perception
```

**阶段 2**: 核心运行时
```bash
m com.genton.agent
m com.genton.policy
m com.genton.memory
```

**阶段 3**: 服务层
```bash
m com.genton.events
m com.genton.action
m com.genton.semantics
m com.genton.context
```

**阶段 4**: 高级功能
```bash
m com.genton.assets
m com.genton.inference
m com.genton.sandbox
```

**阶段 5**: UI 与交互
```bash
m com.genton.ui
m com.genton.call
m com.genton.motion
```

## 循环依赖检测

✅ **无循环依赖** - 所有模块形成有向无环图 (DAG)

## 版本兼容性

所有模块当前版本: **v1**

升级策略:
- 向后兼容: 仅新增方法
- 破坏性变更: 主版本号递增
- 废弃标记: `@deprecated` + 迁移指南

## 外部依赖

| 模块 | Android 框架依赖 |
|------|------------------|
| perception | `android.os.ParcelFileDescriptor` |
| call | `android.telecom.*` (Telecom 框架) |
| motion | `android.hardware.Sensor*` (传感器框架) |
| 所有 | `android.os.Binder`, `android.os.Parcel` |
