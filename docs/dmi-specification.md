# DMI (Device Model Interface) 声明规范

## 1. 概述

DMI（Device Model Interface，设备模型接口）是 AI Native OS 的扩展机制，允许开发者声明式地暴露应用功能和系统能力，供 AI Agent 调用。

DMI 基于 OpenAPI 3.0 规范，并添加 `x-genton` 扩展字段。

**对应任务**: F2.1 DMI声明规范与资产schema补档  
**草案依据**: D11 + F类功能扩展

---

## 2. DMI 声明文件格式

DMI 使用 JSON 或 YAML 格式，遵循 OpenAPI 3.0 规范，文件名约定为 `genton-dmi.json` 或 `genton-dmi.yaml`。

### 2.1 基础结构

```yaml
openapi: 3.0.0
info:
  title: "应用名称 DMI"
  version: "1.0.0"
  description: "应用功能描述"
  x-genton:
    assetType: "dmi"
    category: "weather"  # weather / calendar / communication / media / etc.
    publisher: "com.example.weatherapp"
    requiredPermissions:
      - "android.permission.INTERNET"
    requiredCapabilities:
      - "network.http"

paths:
  /query_weather:
    post:
      summary: "查询天气"
      operationId: "queryWeather"
      x-genton:
        intentActions:
          - "查天气"
          - "天气怎么样"
        executorType: "dmi"
        securityLevel: "L1"  # L0=public / L1=normal / L2=sensitive
        rateLimit:
          maxCallsPerMinute: 10
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
                date:
                  type: string
                  format: date
                  description: "查询日期 (YYYY-MM-DD)"
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
                  condition:
                    type: string
                  humidity:
                    type: number
```

---

## 3. x-genton 扩展字段

### 3.1 全局扩展 (info.x-genton)

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `assetType` | string | 是 | 固定值 "dmi" |
| `category` | string | 是 | 功能分类 |
| `publisher` | string | 是 | 发布者包名 |
| `requiredPermissions` | string[] | 否 | 所需 Android 权限 |
| `requiredCapabilities` | string[] | 否 | 所需能力令牌 |
| `fallbackChain` | string[] | 否 | 降级链（如 DMI 不可用时的备选方案）|

### 3.2 操作扩展 (paths.*.*.x-genton)

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `intentActions` | string[] | 是 | 支持的意图动作列表 |
| `executorType` | string | 是 | 固定值 "dmi" |
| `securityLevel` | string | 是 | 安全级别: L0/L1/L2 |
| `rateLimit` | object | 否 | 速率限制配置 |
| `timeout` | integer | 否 | 超时时间（毫秒），默认 5000 |
| `retryPolicy` | object | 否 | 重试策略 |

---

## 4. DMI 资产注册

DMI 声明文件通过 `AgentAssetLibraryService` 注册为资产：

```java
AssetRegistration registration = new AssetRegistration();

AssetManifest manifest = new AssetManifest();
manifest.assetType = "dmi";
manifest.name = "天气查询";
manifest.description = "通过 DMI 查询天气信息";
manifest.version = "1.0.0";
manifest.category = "weather";
manifest.supportedIntents = new String[]{"查天气", "天气怎么样"};
manifest.executorType = "dmi";
manifest.executorConfig = dmiJsonContent;  // DMI 声明文件内容
manifest.publisherPackage = "com.example.weatherapp";

registration.manifest = manifest;
registration.assetPayload = dmiJsonContent;
registration.signature = sign(dmiJsonContent);
registration.enableImmediately = true;

String[] assetId = new String[1];
int result = assetLibrary.registerAsset(registration, assetId);
```

---

## 5. DMI 执行流程

1. **意图识别**: Agent 识别用户意图 → 查询动作注册表
2. **DMI 解析**: 加载 DMI 声明 → 解析 OpenAPI schema
3. **参数提取**: 从用户输入提取参数 → 验证参数类型
4. **请求构造**: 根据 schema 构造 HTTP 请求
5. **执行调用**: 通过 DMI 桥发送请求
6. **响应解析**: 解析响应 → 返回结果给 Agent

---

## 6. DMI 桥接类型

### 6.1 系统能力桥 (System Capability Bridge)

暴露系统服务给第三方 Agent：

- 通知管理 (NotificationManager)
- 联系人访问 (ContactsProvider)
- 日历操作 (CalendarProvider)
- 媒体控制 (MediaSession)
- 位置服务 (LocationManager)

**示例**: 系统通知 DMI

```yaml
paths:
  /send_notification:
    post:
      summary: "发送系统通知"
      x-genton:
        intentActions: ["发送通知"]
        executorType: "dmi"
        securityLevel: "L2"
        bridge: "system"
        systemService: "notification"
```

### 6.2 App接口桥 (App Interface Bridge)

暴露第三方应用功能：

- Legacy App 适配（无需修改原应用）
- REST API 封装
- Intent 调用封装

**示例**: 第三方天气 App DMI

```yaml
paths:
  /get_weather:
    post:
      x-genton:
        bridge: "app"
        targetPackage: "com.example.weatherapp"
        targetActivity: "com.example.weatherapp.QueryActivity"
        invokeMethod: "intent"  # intent / http / content_provider
```

---

## 7. 安全与鉴权

### 7.1 安全级别

- **L0 (Public)**: 无敏感数据，无需鉴权
- **L1 (Normal)**: 一般数据，需权限声明
- **L2 (Sensitive)**: 敏感数据，需动态授权

### 7.2 鉴权流程

1. DMI 注册时验证 `requiredPermissions`
2. Agent 调用时检查能力令牌 (`CapabilityToken`)
3. L2 级别需用户确认授权

---

## 8. DMI Schema 验证

注册时自动验证：

- ✅ OpenAPI 3.0 格式合规性
- ✅ `x-genton` 字段完整性
- ✅ 权限声明有效性
- ✅ Intent 动作唯一性（不与其他资产冲突）

---

## 9. 版本管理与降级

### 9.1 版本约束

```yaml
info:
  version: "1.2.3"
  x-genton:
    minAndroidSdk: 33
    maxAndroidSdk: 99
```

### 9.2 降级链

```java
String[] fallbackChain = {
    "dmi:weather_api_v2",      // 优先使用 v2
    "dmi:weather_api_v1",      // 降级到 v1
    "macro:open_weather_app"   // 最后使用宏
};
```

---

## 10. 示例完整 DMI 声明

见附录：`genton-dmi-weather-example.yaml`

---

## 11. 工具支持

### 11.1 DMI 生成器

```bash
# 从现有 OpenAPI 文档生成 DMI
genton-dmi generate --input openapi.yaml --output genton-dmi.yaml
```

### 11.2 DMI 验证器

```bash
# 验证 DMI 声明合规性
genton-dmi validate genton-dmi.yaml
```

### 11.3 DMI 注册工具

```bash
# 注册 DMI 到系统
adb shell am broadcast -a com.genton.REGISTER_DMI \
  --es path /data/local/tmp/genton-dmi.yaml
```

---

## 12. 最佳实践

1. **细粒度操作**: 每个 path 对应一个独立功能
2. **明确意图**: `intentActions` 使用自然语言，覆盖多种表达方式
3. **参数简化**: 尽量减少必需参数，提供合理默认值
4. **错误处理**: 定义清晰的错误响应码和描述
5. **速率限制**: 合理设置 `rateLimit` 防止滥用
6. **安全优先**: 敏感操作使用 L2 级别

---

## 13. 参考资料

- OpenAPI 3.0 Specification: https://spec.openapis.org/oas/v3.0.0
- AI Native OS 资产管理: 见 A8.1/A8.2 设计文档
- DMI 桥接实现: 见 F2.2 设计文档
