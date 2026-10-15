# DMI 作者接入指南

> **对应任务**: F2.3 DMI 开发者文档/示例/校验 CLI  
> **适用对象**: 应用开发者、系统服务作者、第三方 SDK 提供商  
> **前置知识**: OpenAPI 3.0 基础、Android 权限模型、AI Native OS 能力令牌体系（A4.2）

---

## 1. DMI 快速入门

### 1.1 什么是 DMI？

DMI（Device Model Interface）是 AI Native OS 的扩展机制，允许开发者**声明式**地暴露应用功能和系统能力，供 AI Agent 自动调用。

**关键特性**:
- ✅ 基于 OpenAPI 3.0 标准，易于迁移现有 REST API
- ✅ 声明式桥接：无需修改原应用代码（Legacy App 适配）
- ✅ 自动参数提取与类型验证
- ✅ 内置安全级别（L0/L1/L2）与能力令牌校验
- ✅ 降级链支持（主 DMI 失败时自动回退到备选方案）

### 1.2 DMI 工作流程

```
1. 编写 DMI 声明文件 (YAML/JSON)
   ↓
2. 使用 dmi-validator CLI 本地校验
   ↓
3. 集成到应用 APK 或系统镜像
   ↓
4. 通过 AgentAssetLibraryService 注册资产
   ↓
5. AI Agent 自动发现并调用
```

---

## 2. 编写第一个 DMI

### 2.1 示例场景：天气查询 DMI

假设你的应用已有天气查询功能，现在将其暴露给 AI Agent。

#### 步骤 1：创建 DMI 声明文件

文件名: `genton-dmi-weather.yaml`

```yaml
openapi: 3.0.0
info:
  title: "天气查询 DMI"
  version: "1.0.0"
  description: "通过 DMI 暴露天气查询功能给 AI Agent"
  x-genton:
    assetType: "dmi"
    category: "weather"
    publisher: "com.example.weatherapp"
    requiredPermissions:
      - "android.permission.INTERNET"
    requiredCapabilities:
      - "network.http"
    fallbackChain:
      - "macro:open_weather_app"

paths:
  /query_weather:
    post:
      summary: "查询天气"
      description: "根据城市名称查询当前天气"
      operationId: "queryWeather"
      x-genton:
        intentActions:
          - "查天气"
          - "天气怎么样"
          - "今天天气"
        executorType: "dmi"
        securityLevel: "L1"
        rateLimit:
          maxCallsPerMinute: 10
        timeout: 5000
      execution:
        bridge:
          type: "http"
          target: "https://api.example.com/v1/weather"
          method: "POST"
          paramsMapping:
            location:
              targetParam: "city"
              transform: "direct"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                location:
                  type: string
                  description: "城市名称"
                  example: "北京"
              required:
                - location
      responses:
        '200':
          description: "查询成功"
          content:
            application/json:
              schema:
                type: object
                properties:
                  temperature:
                    type: number
                    example: 25.5
                  condition:
                    type: string
                    example: "晴"
        '400':
          description: "参数错误"
```

#### 步骤 2：本地校验

```bash
# Windows
dmi-validator.bat genton-dmi-weather.yaml

# Linux/macOS
./dmi-validator.sh genton-dmi-weather.yaml
```

输出示例：
```
✓ OpenAPI 3.0 格式合规
✓ x-genton 字段完整性检查通过
✓ execution 字段验证通过
✓ 存在性规则 EXIST-001~009 全部通过
✓ DMI 声明合规，可以注册
```

#### 步骤 3：集成到应用

将 `genton-dmi-weather.yaml` 放入应用 `assets/` 目录，并在应用启动时注册：

```kotlin
// 在 Application.onCreate() 或 MainActivity.onCreate() 中
val assetLibrary = IAgentAssetLibraryService.Stub.asInterface(
    ServiceManager.getService("agent_asset_library")
)

val dmiContent = assets.open("genton-dmi-weather.yaml").bufferedReader().use { it.readText() }

val manifest = AssetManifest().apply {
    assetType = "dmi"
    name = "天气查询"
    version = "1.0.0"
    category = "weather"
    supportedIntents = arrayOf("查天气", "天气怎么样", "今天天气")
    executorType = "dmi"
    executorConfig = dmiContent
    publisherPackage = packageName
}

val registration = AssetRegistration().apply {
    this.manifest = manifest
    assetPayload = dmiContent
    enableImmediately = true
}

val assetId = arrayOfNulls<String>(1)
val result = assetLibrary.registerAsset(registration, assetId)

if (result == 0) {
    Log.i("DMI", "注册成功，assetId=${assetId[0]}")
} else {
    Log.e("DMI", "注册失败，错误码=$result")
}
```

---

## 3. DMI 桥接类型详解

### 3.1 HTTP 桥接 (type: "http")

**适用场景**: 应用已有 REST API

```yaml
execution:
  bridge:
    type: "http"
    target: "https://api.example.com/v1/weather"
    method: "POST"  # GET/POST/PUT/DELETE/PATCH
    paramsMapping:
      location:
        targetParam: "city"
        transform: "direct"
      date:
        targetParam: "query_date"
        transform: "date_format"  # 日期格式转换
```

**参数转换类型**:
- `direct`: 直接传递
- `to_int`: 转为整数
- `to_string`: 转为字符串
- `to_boolean`: 转为布尔值
- `date_format`: 日期格式化
- `custom`: 自定义 JavaScript 转换脚本（受限沙箱）

### 3.2 System 桥接 (type: "system")

**适用场景**: 系统服务暴露能力

```yaml
execution:
  bridge:
    type: "system"
    target: "notification"  # notification/calendar/contacts/telephony/settings
    method: "notify"
    paramsMapping:
      title:
        targetParam: "contentTitle"
        transform: "direct"
      body:
        targetParam: "contentText"
        transform: "direct"
```

**支持的系统服务**:
- `notification`: NotificationManager（通知管理）
- `calendar`: CalendarProvider（日历操作）
- `contacts`: ContactsProvider（联系人访问）
- `telephony`: TelephonyManager（电话功能）
- `settings`: Settings（系统设置）
- `media`: MediaSession（媒体控制）
- `location`: LocationManager（位置服务）

### 3.3 Intent 桥接 (type: "intent")

**适用场景**: Legacy App 无 API，通过 Intent 调用

```yaml
execution:
  bridge:
    type: "intent"
    target: "com.example.weatherapp/.QueryActivity"  # ComponentName 格式
    paramsMapping:
      location:
        targetParam: "extra_city"  # Intent Extra 键名
        transform: "direct"
```

### 3.4 Content Provider 桥接 (type: "content_provider")

**适用场景**: 通过 Content Provider 访问数据

```yaml
execution:
  bridge:
    type: "content_provider"
    target: "com.example.weatherapp.provider"  # Authority
    method: "query"  # query/insert/update/delete
```

### 3.5 AIDL 桥接 (type: "aidl")

**适用场景**: 系统服务或跨进程调用

```yaml
execution:
  bridge:
    type: "aidl"
    target: "com.example.IWeatherService"  # AIDL 接口名
    method: "queryWeather"
    paramsMapping:
      location:
        targetParam: "city"
        transform: "direct"
```

---

## 4. 安全级别与能力令牌

### 4.1 安全级别定义

| 级别 | 说明 | 使用场景 | 鉴权要求 |
|------|------|----------|----------|
| **L0** | 公开无敏感 | 天气查询、新闻阅读 | 无需鉴权 |
| **L1** | 需声明权限 | 发送短信、读取联系人 | 应用声明 Android 权限 |
| **L2** | 敏感需用户确认 | 银行转账、删除数据 | 能力令牌 + 用户实时确认 |

### 4.2 L2 级别必填字段

L2 级别的 DMI **必须**声明 `capabilityScope` 和 `taintPolicy`，否则校验失败。

```yaml
x-genton:
  securityLevel: "L2"
  capabilityScope:
    - "agent:action:bank_transfer"  # 对齐 A4.2 能力令牌体系
  taintPolicy:
    taintableFields:
      - "amount"
      - "accountNumber"
    taintBehavior: "block"  # block=拒绝请求 / redact=脱敏 / allow=放行
```

**能力令牌命名规范** (对齐 A4.2):
- 格式: `agent:<scope>:<action>`
- 示例:
  - `agent:intent` - 基础意图能力
  - `agent:action:tap` - UI 点击能力
  - `agent:action:dmi_query` - DMI 查询能力
  - `agent:action:bank_transfer` - 银行转账能力

---

## 5. 降级链与容错

### 5.1 fallbackChain 配置

当主 DMI 不可用（网络故障、权限不足、目标应用未安装）时，系统会按序尝试降级链中的备选方案。

```yaml
info:
  x-genton:
    fallbackChain:
      - "dmi:weather_api_v2"      # 优先尝试 v2 API
      - "dmi:weather_api_v1"      # 降级到 v1 API
      - "macro:open_weather_app"  # 最后使用宏打开应用
      - "system:notification"      # 兜底：通知用户失败
```

**降级资产格式**: `<type>:<assetId>`
- `type`: `dmi` / `macro` / `skill` / `system`
- `assetId`: 资产唯一标识符

### 5.2 确定性校验（避免试错）

DMI 桥接器在执行前会执行确定性校验（F2.2 DmiDeterministicValidator），确保操作可成功：

1. ✅ 参数完整性检查
2. ✅ 权限检查（Android 权限 + 能力令牌）
3. ✅ 桥接目标可用性（HTTP 端点/包名/系统服务）
4. ✅ 速率限制检查
5. ✅ Taint 策略校验

**校验失败时自动触发降级链**，避免浪费 Agent 回合。

---

## 6. 意图动作（intentActions）最佳实践

### 6.1 自然语言覆盖

`intentActions` 用于意图匹配，应覆盖用户可能的自然语言表达：

```yaml
intentActions:
  - "查天气"           # 标准表达
  - "天气怎么样"       # 疑问句
  - "今天天气"         # 省略主语
  - "明天天气如何"     # 时间变体
  - "北京天气"         # 地点前置
  - "weather"          # 英文
```

### 6.2 避免冲突

不同 DMI 的 `intentActions` 不能完全重复，否则注册失败。

**❌ 错误示例**:
```yaml
# DMI A
intentActions: ["查天气"]

# DMI B（冲突）
intentActions: ["查天气"]
```

**✅ 正确示例**:
```yaml
# DMI A（通用天气查询）
intentActions: ["查天气", "天气怎么样"]

# DMI B（空气质量专项）
intentActions: ["空气质量", "AQI", "PM2.5"]
```

### 6.3 语义粒度

每个 `path` 应对应**单一功能**，避免语义模糊：

**❌ 粗粒度**:
```yaml
paths:
  /weather_operations:  # 太宽泛
    intentActions: ["天气", "预报", "预警"]
```

**✅ 细粒度**:
```yaml
paths:
  /query_current:
    intentActions: ["查天气", "当前天气"]
  /forecast:
    intentActions: ["天气预报", "未来天气"]
  /alert:
    intentActions: ["天气预警", "极端天气提醒"]
```

---

## 7. 测试与调试

### 7.1 本地校验工具

```bash
# 校验单个文件
dmi-validator.bat genton-dmi.yaml

# 批量校验
dmi-validator.bat --dir ./dmi-assets/

# 生成校验报告
dmi-validator.bat genton-dmi.yaml --report report.md
```

### 7.2 CI 集成

将 DMI 校验加入编译流程（对齐 A7.2 Policy DSL CI）：

```bash
# 在 CI 脚本中调用
./ci/dmi-check.sh

# 或 Windows
.\ci\dmi-check.bat
```

**失败时自动阻止构建**，确保不合规 DMI 无法打包到 APK。

### 7.3 调试技巧

#### 查看注册状态

```bash
adb shell dumpsys agent_asset_library | grep "dmi:"
```

#### 模拟 Agent 调用

```bash
adb shell am broadcast -a com.genton.TEST_DMI \
  --es assetId "dmi:weather_query_v1" \
  --es params '{"location":"北京"}'
```

#### 查看审计日志

```kotlin
val auditService = IAgentAuditService.Stub.asInterface(
    ServiceManager.getService("agent_audit")
)
val query = AuditQuery().apply {
    assetId = "dmi:weather_query_v1"
    startTime = System.currentTimeMillis() - 3600000  // 最近 1 小时
}
val events = auditService.queryEvents(query)
events.forEach { event ->
    Log.d("DMI_Audit", "执行: ${event.actionName}, 结果: ${event.resultStatus}")
}
```

---

## 8. 常见问题

### Q1: DMI 与 Macro 的区别？

| 特性 | DMI | Macro |
|------|-----|-------|
| 声明方式 | OpenAPI 3.0 声明 | 坐标序列录制 |
| 适用场景 | 有 API/Intent 的应用 | 无接口的纯 UI 应用 |
| 参数化 | 支持结构化参数 | 仅支持坐标/文本变量 |
| 机型兼容性 | 高（无坐标依赖） | 低（坐标敏感） |
| 执行速度 | 快（直接调用） | 慢（UI 自动化） |

**推荐策略**: 优先使用 DMI，仅在无接口时使用 Macro 兜底。

### Q2: 如何处理异步操作？

设置 `execution.async: true`，DMI 桥接器会返回 `taskId`，Agent 通过轮询获取结果：

```yaml
execution:
  async: true
  bridge:
    type: "http"
    target: "https://api.example.com/long_task"
```

### Q3: 如何限制调用频率？

使用 `rateLimit` 防止滥用：

```yaml
x-genton:
  rateLimit:
    maxCallsPerMinute: 10  # 每分钟最多 10 次
    maxBurst: 3            # 突发允许 3 次
```

### Q4: 如何更新 DMI 版本？

1. 修改 `info.version`（遵循 SemVer）
2. 重新校验并注册
3. 旧版本会自动禁用（或通过 `enableImmediately: false` 保留）

```yaml
info:
  version: "1.1.0"  # 从 1.0.0 升级
  x-genton:
    fallbackChain:
      - "dmi:weather_v1.0.0"  # 保留旧版本作为降级
```

### Q5: L2 级别需要做什么额外工作？

1. 声明 `capabilityScope`（对齐能力令牌体系）
2. 声明 `taintPolicy`（防止污染数据传播）
3. 用户首次调用时会弹出授权确认 UI

---

## 9. 完整示例集

### 示例 1: 系统通知 DMI

```yaml
openapi: 3.0.0
info:
  title: "系统通知 DMI"
  version: "1.0.0"
  x-genton:
    assetType: "dmi"
    category: "system"
    publisher: "android.system"
paths:
  /send_notification:
    post:
      summary: "发送系统通知"
      x-genton:
        intentActions: ["发送通知", "提醒我"]
        executorType: "dmi"
        securityLevel: "L2"
        capabilityScope:
          - "agent:action:notify"
      execution:
        bridge:
          type: "system"
          target: "notification"
          method: "notify"
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                title: { type: string }
                body: { type: string }
              required: [title, body]
      responses:
        '200':
          description: "发送成功"
```

### 示例 2: Legacy App Intent 调用

```yaml
openapi: 3.0.0
info:
  title: "第三方地图导航 DMI"
  version: "1.0.0"
  x-genton:
    assetType: "dmi"
    category: "navigation"
    publisher: "com.example.wrapper"
    fallbackChain:
      - "macro:open_map_app"
paths:
  /navigate:
    post:
      summary: "导航到目的地"
      x-genton:
        intentActions: ["导航", "去哪里", "带我去"]
        executorType: "dmi"
        securityLevel: "L1"
      execution:
        bridge:
          type: "intent"
          target: "com.google.android.apps.maps/.MapsActivity"
          paramsMapping:
            destination:
              targetParam: "extra_destination"
              transform: "direct"
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                destination: { type: string }
              required: [destination]
      responses:
        '200':
          description: "已启动导航"
```

---

## 10. 参考资料

- **DMI 规范文档**: `docs/dmi-specification.md`
- **Schema 定义**: `docs/schemas/dmi-asset-schema.json`
- **示例库**: `docs/examples/genton-dmi-*.yaml`
- **校验工具源码**: `tools/DmiValidator.java`
- **桥接器实现**: `packages/apps/GentonBrain/src/com/genton/brain/dmi/`

---

## 附录 A: schema 字段速查表

| 字段路径 | 必需 | 类型 | 说明 |
|---------|------|------|------|
| `info.x-genton.assetType` | ✅ | string | 固定 "dmi" |
| `info.x-genton.category` | ✅ | enum | 功能分类 |
| `info.x-genton.publisher` | ✅ | string | 包名 |
| `info.x-genton.requiredPermissions` | ❌ | string[] | Android 权限 |
| `info.x-genton.fallbackChain` | ❌ | string[] | 降级链 |
| `paths.*.*.x-genton.intentActions` | ✅ | string[] | 意图动作 |
| `paths.*.*.x-genton.executorType` | ✅ | string | 固定 "dmi" |
| `paths.*.*.x-genton.securityLevel` | ✅ | enum | L0/L1/L2 |
| `paths.*.*.x-genton.capabilityScope` | L2必需 | string[] | 能力令牌 |
| `paths.*.*.x-genton.taintPolicy` | L2必需 | object | Taint 策略 |
| `paths.*.*.execution.bridge.type` | ✅ | enum | 桥接类型 |
| `paths.*.*.execution.bridge.target` | ✅ | string | 桥接目标 |

---

**版本**: 1.0.0  
**更新日期**: 2026-09-10  
**维护团队**: AI Native OS F2 开发者工具组
