# AI Native OS - AIDL 模块总览

本目录包含 AI Native OS 的 14 个 AIDL 接口模块，定义了系统服务的契约层。

## 模块列表

| 模块 | 包名 | 功能 | 依赖层级 |
|------|------|------|----------|
| [perception](../overlay/frameworks/base/core/java/com/genton/perception/) | `com.genton.perception` | 感知服务 (UI树/屏幕/通知) | L0 |
| [audit](../overlay/frameworks/base/core/java/com/genton/audit/) | `com.genton.audit` | 审计服务 (哈希链/活性流) | L0 |
| [agent](../overlay/frameworks/base/core/java/com/genton/agent/) | `com.genton.agent` | Agent 运行时 (ACB/会话) | L1 |
| [policy](../overlay/frameworks/base/core/java/com/genton/policy/) | `com.genton.policy` | 策略引擎 (令牌/确认) | L1 |
| [memory](com/genton/memory/) | `com.genton.memory` | 记忆服务 (三层分区) | L1 |
| [events](../overlay/frameworks/base/core/java/com/genton/events/) | `com.genton.events` | 事件流 (9组事件源) | L2 |
| [action](../overlay/frameworks/base/core/java/com/genton/action/) | `com.genton.action` | 动作执行 (执行中介) | L2 |
| [semantics](com/genton/semantics/) | `com.genton.semantics` | 语义服务 (本体图) | L2 |
| [context](com/genton/context/) | `com.genton.context` | 上下文服务 (ContextFrame) | L2 |
| [assets](com/genton/assets/) | `com.genton.assets` | 资产目录 (Skill/DMI) | L3 |
| [inference](../overlay/frameworks/base/core/java/com/genton/inference/) | `com.genton.inference` | 推理路由 (端云决策) | L3 |
| [sandbox](com/genton/sandbox/) | `com.genton.sandbox` | 沙箱执行 (Isolate) | L3 |
| [ui](../overlay/frameworks/base/core/java/com/genton/ui/) | `com.genton.ui` | UI 契约 (会话桥/确认宿主) | L4 |
| [call](com/genton/call/) | `com.genton.call` | 通话门控 (CallGate) | L4 |
| [motion](../overlay/frameworks/base/core/java/com/genton/motion/) | `com.genton.motion` | 姿态交互 (MotionHub) | L5 |

## 目录结构

```
aidl/
├── perception/
│   ├── Android.bp
│   └── com/genton/perception/
│       ├── IAgentUiTreeService.aidl
│       ├── IAgentScreenService.aidl
│       ├── IAgentNotificationService.aidl
│       └── [数据类型 Parcelable]
├── action/
│   ├── Android.bp
│   └── com/genton/action/
│       ├── IAgentActionService.aidl
│       └── [数据类型 Parcelable]
├── agent/
│   ├── Android.bp
│   └── com/genton/agent/
│       ├── IAgentRuntimeService.aidl
│       └── [数据类型 Parcelable]
├── ... (其他 11 个模块)
├── DEPENDENCIES.md          # 依赖关系文档
└── README.md                # 本文件
```

## 编译

### 编译所有模块

```bash
cd ~/genton-aosp
source build/envsetup.sh
lunch genton_arm64-userdebug

# 编译所有 AIDL 模块
m $(cat <<EOF
com.genton.perception
com.genton.audit
com.genton.agent
com.genton.policy
com.genton.memory
com.genton.events
com.genton.action
com.genton.semantics
com.genton.context
com.genton.assets
com.genton.inference
com.genton.sandbox
com.genton.ui
com.genton.call
com.genton.motion
EOF
)
```

### 按层级编译

```bash
# Layer 0 (基础)
m com.genton.audit com.genton.perception

# Layer 1 (核心)
m com.genton.agent com.genton.policy com.genton.memory

# Layer 2 (服务)
m com.genton.events com.genton.action com.genton.semantics com.genton.context

# Layer 3 (高级)
m com.genton.assets com.genton.inference com.genton.sandbox

# Layer 4-5 (UI/交互)
m com.genton.ui com.genton.call com.genton.motion
```

### 单独编译模块

```bash
m com.genton.perception  # 仅编译 perception 模块
```

## 生成产物

编译成功后，生成以下产物：

```
out/soong/.intermediates/
└── frameworks/aidl/genton/
    ├── perception/
    │   ├── android_common/
    │   │   └── com.genton.perception.jar
    │   └── gen/
    │       └── com/genton/perception/
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
        "com.genton.perception",
        "com.genton.agent",
    ],
}

// 代码中使用
import com.genton.perception.IAgentUiTreeService;
import com.genton.agent.IAgentRuntimeService;

// 获取服务
IAgentUiTreeService uiTreeService = IAgentUiTreeService.Stub.asInterface(
    ServiceManager.getService("genton_uitree"));
```

### C++ 应用

```cpp
// Android.bp 添加依赖
cc_binary {
    name: "my_native_app",
    srcs: ["main.cpp"],
    shared_libs: [
        "com.genton.perception-cpp",
    ],
}

// 代码中使用
#include <com/genton/perception/IAgentUiTreeService.h>

using namespace com::genton::perception;
```

## 稳定性保证

所有 AIDL 模块均标记为 `stability: "vintf"`，表示：

- ✅ **向后兼容**: 仅允许新增方法
- ✅ **版本锁定**: 每个版本冻结后不可修改
- ✅ **ABI 稳定**: 保证二进制接口兼容性

## 权限与可见性

每个模块的 `visibility` 字段限制了可访问范围：

- `//frameworks/base/services/core` - SystemServer 服务实现
- `//packages/apps/GentonBrain` - 大脑特权 App
- `//packages/apps/SystemUI` - SystemUI 扩展
- `//packages/apps/Settings` - 设置 App (隐私仪表盘)

第三方应用**无法直接访问**这些 AIDL 接口。

## 依赖关系

详见 [DEPENDENCIES.md](DEPENDENCIES.md)

## 版本管理

当前所有模块版本: **v1**

版本升级流程:
1. 创建新版本目录: `aidl_api/com.genton.xxx/v2/`
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
m com.genton.agent  # 先编译依赖
m com.genton.events  # 再编译本模块
```

### 运行时错误: "ClassNotFoundException"

确保应用 `Android.bp` 包含所需依赖：
```bp
static_libs: [
    "com.genton.perception",
]
```

### SELinux 拒绝

检查 SELinux 策略是否允许访问：
```bash
adb shell dmesg | grep avc
```

## 下一步

- **A1.4**: 创建权限清单 (privapp-permissions.xml)
- **A1.5**: 配置 SELinux 策略 (system/sepolicy/genton/)
- **服务端实现**: 在 `frameworks/base/services/genton/` 创建服务实现

---

**A1.3 AIDL 模块骨架已完成 ✅**
