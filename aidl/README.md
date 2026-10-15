# AI Native OS - AIDL 模块总览

本目录包含 AI Native OS 的 14 个 AIDL 接口模块，定义了系统服务的契约层。

## 模块列表

| 模块 | 包名 | 功能 | 依赖层级 |
|------|------|------|----------|
| [perception](perception/) | `com.ainative.perception` | 感知服务 (UI树/屏幕/通知) | L0 |
| [audit](audit/) | `com.ainative.audit` | 审计服务 (哈希链/活性流) | L0 |
| [agent](agent/) | `com.ainative.agent` | Agent 运行时 (ACB/会话) | L1 |
| [policy](policy/) | `com.ainative.policy` | 策略引擎 (令牌/确认) | L1 |
| [memory](memory/) | `com.ainative.memory` | 记忆服务 (三层分区) | L1 |
| [events](events/) | `com.ainative.events` | 事件流 (9组事件源) | L2 |
| [action](action/) | `com.ainative.action` | 动作执行 (执行中介) | L2 |
| [semantics](semantics/) | `com.ainative.semantics` | 语义服务 (本体图) | L2 |
| [context](context/) | `com.ainative.context` | 上下文服务 (ContextFrame) | L2 |
| [assets](assets/) | `com.ainative.assets` | 资产目录 (Skill/DMI) | L3 |
| [inference](inference/) | `com.ainative.inference` | 推理路由 (端云决策) | L3 |
| [sandbox](sandbox/) | `com.ainative.sandbox` | 沙箱执行 (Isolate) | L3 |
| [ui](ui/) | `com.ainative.ui` | UI 契约 (会话桥/确认宿主) | L4 |
| [call](call/) | `com.ainative.call` | 通话门控 (CallGate) | L4 |
| [motion](motion/) | `com.ainative.motion` | 姿态交互 (MotionHub) | L5 |

## 目录结构

```
aidl/
├── perception/
│   ├── Android.bp
│   └── com/ainative/perception/
│       ├── IAgentUiTreeService.aidl
│       ├── IAgentScreenService.aidl
│       ├── IAgentNotificationService.aidl
│       └── [数据类型 Parcelable]
├── action/
│   ├── Android.bp
│   └── com/ainative/action/
│       ├── IAgentActionService.aidl
│       └── [数据类型 Parcelable]
├── agent/
│   ├── Android.bp
│   └── com/ainative/agent/
│       ├── IAgentRuntimeService.aidl
│       └── [数据类型 Parcelable]
├── ... (其他 11 个模块)
├── DEPENDENCIES.md          # 依赖关系文档
└── README.md                # 本文件
```

## 编译

### 编译所有模块

```bash
cd ~/ainative-aosp
source build/envsetup.sh
lunch ainative_arm64-userdebug

# 编译所有 AIDL 模块
m $(cat <<EOF
com.ainative.perception
com.ainative.audit
com.ainative.agent
com.ainative.policy
com.ainative.memory
com.ainative.events
com.ainative.action
com.ainative.semantics
com.ainative.context
com.ainative.assets
com.ainative.inference
com.ainative.sandbox
com.ainative.ui
com.ainative.call
com.ainative.motion
EOF
)
```

### 按层级编译

```bash
# Layer 0 (基础)
m com.ainative.audit com.ainative.perception

# Layer 1 (核心)
m com.ainative.agent com.ainative.policy com.ainative.memory

# Layer 2 (服务)
m com.ainative.events com.ainative.action com.ainative.semantics com.ainative.context

# Layer 3 (高级)
m com.ainative.assets com.ainative.inference com.ainative.sandbox

# Layer 4-5 (UI/交互)
m com.ainative.ui com.ainative.call com.ainative.motion
```

### 单独编译模块

```bash
m com.ainative.perception  # 仅编译 perception 模块
```

## 生成产物

编译成功后，生成以下产物：

```
out/soong/.intermediates/
└── frameworks/aidl/ainative/
    ├── perception/
    │   ├── android_common/
    │   │   └── com.ainative.perception.jar
    │   └── gen/
    │       └── com/ainative/perception/
    ├── action/
    │   └── ...
    └── ... (其他模块)
```

## 在应用中使用

### Java 应用

```java
// Android.bp 添加依赖
java_library {
    name: "MyApp",
    srcs: ["src/**/*.java"],
    static_libs: [
        "com.ainative.perception",
        "com.ainative.agent",
    ],
}

// 代码中使用
import com.ainative.perception.IAgentUiTreeService;
import com.ainative.agent.IAgentRuntimeService;

// 获取服务
IAgentUiTreeService uiTreeService = IAgentUiTreeService.Stub.asInterface(
    ServiceManager.getService("ainative_uitree"));
```

### C++ 应用

```cpp
// Android.bp 添加依赖
cc_binary {
    name: "my_native_app",
    srcs: ["main.cpp"],
    shared_libs: [
        "com.ainative.perception-cpp",
    ],
}

// 代码中使用
#include <com/ainative/perception/IAgentUiTreeService.h>

using namespace com::ainative::perception;
```

## 稳定性保证

所有 AIDL 模块均标记为 `stability: "vintf"`，表示：

- ✅ **向后兼容**: 仅允许新增方法
- ✅ **版本锁定**: 每个版本冻结后不可修改
- ✅ **ABI 稳定**: 保证二进制接口兼容性

## 权限与可见性

每个模块的 `visibility` 字段限制了可访问范围：

- `//frameworks/base/services/core` - SystemServer 服务实现
- `//packages/apps/AINativeBrain` - 大脑特权 App
- `//packages/apps/SystemUI` - SystemUI 扩展
- `//packages/apps/Settings` - 设置 App (隐私仪表盘)

第三方应用**无法直接访问**这些 AIDL 接口。

## 依赖关系

详见 [DEPENDENCIES.md](DEPENDENCIES.md)

## 版本管理

当前所有模块版本: **v1**

版本升级流程:
1. 创建新版本目录: `aidl_api/com.ainative.xxx/v2/`
2. 更新 `versions_with_info`
3. 确保向后兼容或提供迁移路径
4. 更新文档

## 开发指南

### 新增 AIDL 方法

```aidl
// ✅ 正确: 在接口末尾追加
interface IAgentUiTreeService {
    UiSnapshot captureUiTree();
    // 新增方法 (版本 2)
    void newMethod() = 1;  // 显式序列号
}
```

```aidl
// ❌ 错误: 修改已有方法签名
interface IAgentUiTreeService {
    UiSnapshot captureUiTree(int flags);  // 破坏兼容性!
}
```

### 新增 Parcelable 字段

```java
// ✅ 正确: 在类末尾追加
parcelable UiSnapshot {
    String xmlContent;
    long timestampMs;
    // 新增字段 (版本 2)
    int flags;  // 默认值 0
}
```

## 故障排查

### 编译错误: "No rule to make target"

检查依赖模块是否已编译：
```bash
m com.ainative.agent  # 先编译依赖
m com.ainative.events  # 再编译本模块
```

### 运行时错误: "ClassNotFoundException"

确保应用 `Android.bp` 包含所需依赖：
```bp
static_libs: [
    "com.ainative.perception",
]
```

### SELinux 拒绝

检查 SELinux 策略是否允许访问：
```bash
adb shell dmesg | grep avc
```

## 下一步

- **A1.4**: 创建权限清单 (privapp-permissions.xml)
- **A1.5**: 配置 SELinux 策略 (system/sepolicy/ainative/)
- **服务端实现**: 在 `frameworks/base/services/ainative/` 创建服务实现

---

**A1.3 AIDL 模块骨架已完成 ✅**
