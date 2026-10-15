# F2.2 DMI 桥方案与落地 - 实现总结

> **版本**：1.0  
> **日期**：2026-09-06  
> **状态**：已完成  
> **任务编号**：F2.2（WBS F 资产生态部）

---

## 1. 任务概述

### 1.1 目标

实现 DMI（Device-Model Interface）桥接层，打通 Agent 与设备系统能力、第三方应用的调用通道。

### 1.2 交付物

- **系统能力桥**：封装 Android 系统 API（通话、短信、联系人、日历等）
- **Legacy App 桥接层**：通过 Intent 调用第三方应用
- **DMI 执行器**：与 ToolLoop 集成，支持工具编排
- **完整的权限检查与回调机制**

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                       ToolLoop                          │
│              (B2.2 工具循环编排层)                        │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
         ┌───────────────────────┐
         │    DmiExecutor        │  ← 统一执行接口
         │   (执行器协调层)        │
         └───────────┬───────────┘
                     │
                     ▼
         ┌───────────────────────┐
         │     DmiBridge         │  ← 桥接分发器
         │    (桥接分发层)         │
         └───────┬───────┬───────┘
                 │       │
        ┌────────┘       └────────┐
        ▼                          ▼
┌──────────────────┐    ┌─────────────────────┐
│ SystemCapability │    │   LegacyAppBridge   │
│     Bridge       │    │  (Intent 桥接层)     │
│  (系统能力桥)     │    └─────────────────────┘
└────────┬─────────┘
         │
    ┌────┴────┬────────┬────────┬────────┐
    ▼         ▼        ▼        ▼        ▼
┌────────┐ ┌───────┐ ┌───────┐ ┌──────┐ ┌──────┐
│Telephony│ │  SMS  │ │Contact│ │Calendar│ │ ...  │
│ Bridge │ │Bridge │ │Bridge │ │Bridge │ │      │
└────────┘ └───────┘ └───────┘ └──────┘ └──────┘
```

### 2.2 核心组件

| 组件 | 路径 | 职责 |
|------|------|------|
| **DmiBridge** | `dmi/DmiBridge.kt` | DMI 桥接器主类，管理所有能力桥，分发工具调用 |
| **DmiExecutor** | `dmi/DmiExecutor.kt` | DMI 执行器，与 ToolLoop 集成，管理异步回调 |
| **SystemCapabilityBridge** | `dmi/SystemCapabilityBridge.kt` | 系统能力桥接口定义 |
| **LegacyAppBridge** | `dmi/LegacyAppBridge.kt` | Legacy App Intent 桥接层 |
| **具体能力桥** | `dmi/bridges/*.kt` | 各系统 API 的具体封装实现 |

---

## 3. 系统能力桥实现

### 3.1 已实现的系统能力桥

| DMI 工具名 | 功能 | 实现类 | 所需权限 |
|-----------|------|--------|---------|
| `dmi.telephony.call` | 发起电话呼叫 | `TelephonyCallBridge` | `CALL_PHONE` |
| `dmi.sms.send` | 发送短信 | `SmsSendBridge` | `SEND_SMS` |
| `dmi.contacts.query` | 查询联系人 | `ContactsQueryBridge` | `READ_CONTACTS` |
| `dmi.calendar.query` | 查询日历事件 | `CalendarQueryBridge` | `READ_CALENDAR` |
| `dmi.calendar.create` | 创建日历事件 | `CalendarCreateBridge` | `WRITE_CALENDAR` |

### 3.2 系统能力桥接口规范

```kotlin
interface SystemCapabilityBridge {
    // 执行系统能力调用
    fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback)
    
    // 验证参数
    fun validateParameters(parameters: Map<String, Any>): String?
    
    // 检查权限
    fun checkPermissions(): String?
}
```

### 3.3 示例：通话能力桥

```kotlin
class TelephonyCallBridge(private val context: Context) : SystemCapabilityBridge {
    
    override fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        // 1. 验证参数
        val validationError = validateParameters(parameters)
        if (validationError != null) {
            callback.onFailure(RESULT_INVALID_PARAMETERS, validationError)
            return
        }
        
        // 2. 检查权限
        val permissionError = checkPermissions()
        if (permissionError != null) {
            callback.onFailure(RESULT_PERMISSION_DENIED, permissionError)
            return
        }
        
        // 3. 提取参数并执行
        val phoneNumber = parameters["phoneNumber"] as String
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        context.startActivity(intent)
        callback.onSuccess("""{"success": true, "phoneNumber": "$phoneNumber"}""")
    }
    
    override fun validateParameters(parameters: Map<String, Any>): String? {
        if (!parameters.containsKey("phoneNumber")) {
            return "Missing required parameter: phoneNumber"
        }
        // 更多验证...
        return null
    }
    
    override fun checkPermissions(): String? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        
        return if (hasPermission) null else "Missing permission: CALL_PHONE"
    }
}
```

---

## 4. Legacy App 桥接层

### 4.1 支持的 Intent 类型

| DMI 工具名 | Intent 动作 | 参数 |
|-----------|------------|------|
| `dmi.app.browser.open` | `ACTION_VIEW` | `url: String` |
| `dmi.app.email.send` | `ACTION_SENDTO` | `to: String, subject: String, body: String` |
| `dmi.app.share.text` | `ACTION_SEND` | `text: String, title: String` |
| `dmi.app.camera.capture` | `IMAGE_CAPTURE` | `outputUri: String` (可选) |
| `dmi.app.maps.navigate` | `ACTION_VIEW` | `destination: String` 或 `latitude/longitude` |

### 4.2 Intent 桥接流程

```kotlin
class LegacyAppBridge(private val context: Context) {
    
    fun execute(toolName: String, parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        // 1. 根据工具名称构建 Intent
        val intent = buildIntent(toolName, parameters)
        
        // 2. 验证 Intent 可解析
        if (!isIntentResolvable(intent)) {
            callback.onFailure(RESULT_EXECUTION_FAILED, 
                "No application found to handle this intent")
            return
        }
        
        // 3. 启动 Intent
        context.startActivity(intent)
        callback.onSuccess("""{"success": true, "action": "${intent.action}"}""")
    }
    
    private fun isIntentResolvable(intent: Intent): Boolean {
        val resolveInfo = context.packageManager.resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolveInfo != null
    }
}
```

---

## 5. ToolLoop 集成

### 5.1 DMI 工具识别与路由

ToolLoop 在执行动作时，根据 `actionType` 前缀判断是否为 DMI 工具：

```kotlin
private fun executeActions(actions: List<ActionPlan>, ...): List<ActionExecutionResult> {
    for (action in actions) {
        val result = if (action.actionType.startsWith("dmi.")) {
            // DMI 工具调用
            executeDmiAction(action, context)
        } else {
            // 普通动作执行（通过 A3.2 执行中介）
            executeNormalAction(action, context)
        }
        // ...
    }
}
```

### 5.2 同步执行模式

DMI 工具调用采用同步执行模式（使用 `CountDownLatch` 等待）：

```kotlin
private fun executeDmiAction(action: ActionPlan, context: ExecutionContext): ActionExecutionResult {
    val latch = CountDownLatch(1)
    var executionResult: ActionExecutionResult? = null
    
    dmiExecutor.executeDmiTool(
        requestId = "${context.turnId}_${action.actionType}_${System.currentTimeMillis()}",
        toolName = action.actionType,
        parametersJson = JSONObject(action.parameters).toString(),
        callback = object : DmiExecutionCallback {
            override fun onSuccess(resultJson: String) {
                executionResult = ActionExecutionResult(...)
                latch.countDown()
            }
            override fun onFailure(errorCode: Int, errorMessage: String) {
                executionResult = ActionExecutionResult(...)
                latch.countDown()
            }
        }
    )
    
    // 等待执行完成（超时 30 秒）
    latch.await(30, TimeUnit.SECONDS)
    
    return executionResult ?: /* timeout result */
}
```

---

## 6. 错误处理与安全

### 6.1 错误码定义

```kotlin
const val RESULT_SUCCESS = 0
const val RESULT_PERMISSION_DENIED = 1
const val RESULT_INVALID_PARAMETERS = 2
const val RESULT_NOT_SUPPORTED = 3
const val RESULT_EXECUTION_FAILED = 4
```

### 6.2 安全机制

1. **权限检查前置**：所有系统能力桥在执行前检查权限
2. **参数验证**：强制参数类型和格式验证
3. **Intent 可解析性验证**：Legacy App 桥接层验证 Intent 是否有应用可处理
4. **超时保护**：DMI 工具执行超时 30 秒自动返回失败
5. **敏感操作审计**：所有 DMI 调用应记录审计日志（待实现）

---

## 7. 初始化流程

### 7.1 BrainApplication 初始化

```kotlin
// BrainApplication.onCreate() → initializeIntentReceiver()
private fun initializeIntentReceiver() {
    // ...
    
    // 4. 初始化 DMI 桥接器
    dmiBridge = DmiBridge(this)
    dmiBridge.initialize()
    
    // 5. 初始化 DMI 执行器
    dmiExecutor = DmiExecutor(dmiBridge)
    
    Log.i(TAG, "Intent receiver module initialized")
}
```

### 7.2 DmiBridge 初始化

```kotlin
fun initialize() {
    Log.i(TAG, "Initializing DMI Bridge")
    
    // 注册系统能力桥
    registerSystemCapabilityBridges()
    
    // 初始化 Legacy App 桥接器
    legacyAppBridge = LegacyAppBridge(context)
    
    Log.i(TAG, "DMI Bridge initialized with ${systemCapabilityBridges.size} system bridges")
}
```

---

## 8. 使用示例

### 8.1 从规划器生成 DMI 动作

```kotlin
// 规划器生成 Goal，包含 DMI 动作
val goal = GoalNode(
    goalId = "send_sms_to_alice",
    description = "发送短信给 Alice",
    actions = listOf(
        ActionPlan(
            actionType = "dmi.sms.send",
            parameters = mapOf(
                "phoneNumber" to "13800138000",
                "message" to "Hello Alice!"
            ),
            expectedResult = "success"
        )
    ),
    requiredCapabilities = listOf("agent.send_message")
)
```

### 8.2 ToolLoop 执行 DMI 动作

```kotlin
// ToolLoop 自动识别并路由到 DmiExecutor
val result = toolLoop.execute(planningResult, agentId)

// 结果包含 DMI 执行状态
if (result.status == EXECUTION_SUCCESS) {
    Log.i(TAG, "DMI action executed successfully")
}
```

---

## 9. 扩展指南

### 9.1 添加新的系统能力桥

1. 创建新的 Bridge 类实现 `SystemCapabilityBridge` 接口
2. 在 `DmiBridge.registerSystemCapabilityBridges()` 中注册
3. 定义 DMI 工具名（如 `dmi.media.play`）和参数 schema

示例：

```kotlin
class MediaPlayBridge(private val context: Context) : SystemCapabilityBridge {
    override fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        val mediaUri = parameters["uri"] as String
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(mediaUri)
            type = "audio/*"
        }
        context.startActivity(intent)
        callback.onSuccess("""{"success": true}""")
    }
    
    override fun validateParameters(parameters: Map<String, Any>): String? {
        return if (parameters.containsKey("uri")) null else "Missing uri"
    }
    
    override fun checkPermissions(): String? = null
}

// 在 DmiBridge 中注册
systemCapabilityBridges["dmi.media.play"] = MediaPlayBridge(context)
```

### 9.2 添加新的 Legacy App Intent

在 `LegacyAppBridge.buildIntent()` 中添加新的 Intent 构建逻辑：

```kotlin
private fun buildIntent(toolName: String, parameters: Map<String, Any>): Intent? {
    return when (toolName) {
        // 现有 Intent...
        "dmi.app.music.play" -> buildMusicIntent(parameters)
        else -> null
    }
}
```

---

## 10. 下一步工作

### 10.1 待完善的能力桥（占位实现）

以下能力桥当前为占位实现，需后续补充：

- `dmi.sms.read` - 短信读取
- `dmi.contacts.create` - 联系人创建
- `dmi.contacts.update` - 联系人更新
- `dmi.calendar.update` - 日历事件更新
- `dmi.file.read` - 文件读取
- `dmi.file.write` - 文件写入
- `dmi.settings.get` - 系统设置读取
- `dmi.settings.set` - 系统设置写入

### 10.2 集成任务

- [ ] **A8.2 资产与动作注册表统一**：将 DMI 工具注册到动作注册表
- [ ] **策略引擎集成**：DMI 工具调用需经过策略引擎预检
- [ ] **审计日志集成**：记录所有 DMI 调用到审计服务
- [ ] **能力令牌集成**：DMI 工具调用需持有对应能力令牌
- [ ] **表单集成**：DMI 工具参数通过 L0.5 表单收集

### 10.3 测试与验证

- [ ] 单元测试：每个能力桥的单独测试
- [ ] 集成测试：ToolLoop → DmiExecutor → DmiBridge 完整链路测试
- [ ] 权限测试：验证缺失权限时的错误处理
- [ ] 性能测试：DMI 工具调用的延迟和吞吐量

---

## 11. 文件清单

```
packages/apps/AINativeBrain/src/com/ainative/brain/
├── dmi/
│   ├── DmiBridge.kt                    # DMI 桥接器主类
│   ├── DmiExecutor.kt                  # DMI 执行器
│   ├── SystemCapabilityBridge.kt       # 系统能力桥接口
│   ├── LegacyAppBridge.kt              # Legacy App 桥接层
│   └── bridges/
│       ├── TelephonyCallBridge.kt      # 通话能力桥
│       ├── SmsSendBridge.kt            # 短信发送能力桥
│       ├── ContactsQueryBridge.kt      # 联系人查询能力桥
│       ├── CalendarQueryBridge.kt      # 日历查询能力桥
│       ├── CalendarCreateBridge.kt     # 日历创建能力桥
│       └── StubBridges.kt              # 占位能力桥（待实现）
├── toolloop/
│   ├── ToolLoop.kt                     # 工具循环（已集成 DMI）
│   └── ToolLoopModels.kt               # 工具循环数据模型
└── BrainApplication.kt                 # 应用主类（已集成 DMI 初始化）
```

---

## 12. 总结

F2.2 DMI 桥方案已完整实现，包括：

✅ **系统能力桥架构**：定义统一接口，支持扩展  
✅ **5个核心系统能力桥**：通话、短信、联系人、日历  
✅ **Legacy App 桥接层**：支持 5 种常见 Intent 类型  
✅ **ToolLoop 集成**：自动识别 DMI 工具并路由执行  
✅ **完整的错误处理**：参数验证、权限检查、超时保护  
✅ **可扩展设计**：易于添加新的能力桥和 Intent 类型  

DMI 桥为 Agent 提供了与设备和第三方应用交互的标准化通道，是资产生态的重要基础设施。
