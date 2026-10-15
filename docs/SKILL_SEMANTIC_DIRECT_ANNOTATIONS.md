# 首批 Skill 资产语义直连 execution 标注清单

> 任务编号：F3.2  
> 版本：Skill 资产 v1  
> 更新时间：2026-09-10  
> 用途：A4 真机演示弹药 + 语义直连（D15）意图匹配输入  
> 相关文档：[SKILL_AUTHOR_GUIDE.md](./SKILL_AUTHOR_GUIDE.md)、[skill-asset-schema.json](./schemas/skill-asset-schema.json)

---

## 1. 标注字段定义

| 字段 | 位置 | 取值 | 语义 |
|---|---|---|---|
| `metadata.executionMode` | Skill 声明 | `semantic_direct` / `macro_inject` | 语义直连判定，映射 `AssetManifest.executionMode`：`0`=免渲染直连，`1`=有参宏需渲染目标 |
| `metadata.semanticTriggers` | Skill 声明 | 自然语言短句数组（≤20） | 免 LLM 管线的意图匹配输入；与同能力 DMI 的 `intentActions` 不得完全重复 |
| `metadata.riskLevel` | Skill 声明 | `0` / `1` / `2` | 0=无副作用，1=一般副作用，2=敏感操作 |
| `metadata.confirmation` | Skill 声明 | `default` / `always` / `never` | 映射 `AssetManifest.confirmation`：`0`/`1`/`2` |
| `metadata.capability` | Skill 声明 | 能力令牌 | 与 `requiredCapabilities` 一并用于执行前能力校验 |

**判定归属**：宿主不自行推断 `executionMode`，以资产声明为准（见 [轨道A-L0.5表单收参与语义直连机制草案.md](../轨道A-L0.5表单收参与语义直连机制草案.md)、[AssetManifest.aidl](../aidl/com/genton/assets/AssetManifest.aidl)）。

---

## 2. 首批资产标注总表

| # | Skill ID | 文件 | 域 | capability | riskLevel | confirmation | executionMode | 编排结构 | 引用系统能力桥 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `skill:notification.send_scheduled` | [send_scheduled_notification.json](../assets/skills/send_scheduled_notification.json) | notification | `agent:action:notify` | 1 | default | semantic_direct | sequential → condition → system | `dmi:calendar.query`、`dmi:notification.notify` |
| 2 | `skill:notification.batch_remind` | [notification_batch_remind.json](../assets/skills/notification_batch_remind.json) | notification | `agent:action:notify` | 1 | default | semantic_direct | sequential → loop → system | `dmi:calendar.query`、`dmi:notification.notify` |
| 3 | `skill:calendar.create_event` | [calendar_create_event.json](../assets/skills/calendar_create_event.json) | calendar | `agent:action:calendar_write` | 1 | default | semantic_direct | sequential → dmi → condition → dmi/system | `dmi:calendar.query`、`dmi:calendar.create`、`dmi:notification.notify` |
| 4 | `skill:calendar.query_agenda` | [calendar_query_agenda.json](../assets/skills/calendar_query_agenda.json) | calendar | `agent:action:calendar_read` | 0 | never | semantic_direct | sequential → dmi | `dmi:calendar.query` |
| 5 | `skill:telephony.call_contact` | [telephony_call_contact.json](../assets/skills/telephony_call_contact.json) | communication | `agent:action:call` | 2 | always | semantic_direct | sequential → dmi → condition → dmi/system | `dmi:contacts.query`、`dmi:telephony.call`、`dmi:notification.notify` |
| 6 | `skill:telephony.missed_call_autoreply` | [telephony_missed_call_autoreply.json](../assets/skills/telephony_missed_call_autoreply.json) | communication | `agent:action:sms_send` | 2 | always | semantic_direct | sequential → dmi → condition → dmi | `dmi:contacts.query`、`dmi:sms.send` |

**共同属性**：`publisher = android.system`，`version = 1.0.0`，`executionMode` 全部为 `semantic_direct`（首批资产均为免渲染直连路径，无 `macro_inject` 样例）。

---

## 3. 逐资产标注详情

### 3.1 `skill:calendar.query_agenda`（只读基线）

- **semanticTriggers**：`我今天有什么安排`、`查一下我的日程`、`看看我的日历`
- **requiredCapabilities**：`agent:action:calendar_read`
- **必填参数**：`startTime`、`endTime`（Unix 毫秒）；可选 `limit`（默认 10）
- **返回**：`events[]`、`count`
- **演示价值**：riskLevel 0 + confirmation never，可用于验证"无确认直连"与只读路径不触发确认面板

### 3.2 `skill:notification.send_scheduled`（条件分支）

- **semanticTriggers**：`提醒我今天的日程`、`有会议就通知我`、`日程提醒`
- **requiredCapabilities**：`agent:action:notify`、`agent:action:calendar_read`
- **必填参数**：`startTime`、`endTime`
- **分支**：`greater_than($.step.query_calendar.data.events.length, 0)` → 有日程发 high 优先级通知；否则发 normal"暂无日程安排"
- **fallbackChain**：`skill:notification.batch_remind`
- **错误处理**：`retry`（maxRetries=1，retryDelayMs=1000）

### 3.3 `skill:notification.batch_remind`（循环编排）

- **semanticTriggers**：`逐条提醒我的日程`、`今天的每个会议都提醒我`、`把日程一条条念给我`
- **requiredCapabilities**：`agent:action:notify`、`agent:action:calendar_read`
- **必填参数**：`startTime`、`endTime`
- **循环**：`iterateOver = $.step.query_calendar.data.events`，`itemVariable = event`，`maxIterations = 5`，循环体为 `system:notification.notify`（body = `{{$.event.title}}`）
- **返回**：`iterations`、`results[]`
- **错误处理**：`continue`（单条失败不中断）

### 3.4 `skill:calendar.create_event`（冲突检测）

- **semanticTriggers**：`帮我建个日程`、`安排一个会议`、`新增日历事件`
- **requiredCapabilities**：`agent:action:calendar_write`、`agent:action:calendar_read`、`agent:action:notify`
- **必填参数**：`title`、`startTime`、`endTime`；可选 `location`、`description`
- **分支**：存在冲突日程 → 通知提示且不创建；无冲突 → `dmi:calendar.create`
- **错误处理**：`abort`

### 3.5 `skill:telephony.call_contact`（敏感操作 + 强制确认）

- **semanticTriggers**：`打电话给张三`、`帮我呼叫联系人`、`拨打李四的电话`
- **requiredCapabilities**：`agent:action:call`、`agent:action:contacts_read`、`agent:action:notify`
- **必填参数**：`contactName`
- **分支**：命中联系人 → `dmi:telephony.call`（`phoneNumber` 取 `contacts[0].phoneNumbers[0].number`）；未命中 → 通知提示
- **演示价值**：riskLevel 2 + confirmation always，用于验证确认面板与令牌链协同

### 3.6 `skill:telephony.missed_call_autoreply`（多分支短信回复）

- **semanticTriggers**：`给未接来电回条短信`、`自动回复刚才的电话`、`帮我回复未接来电`
- **requiredCapabilities**：`agent:action:sms_send`、`agent:action:contacts_read`
- **必填参数**：`phoneNumber`；可选 `contactName`
- **分支**：已知联系人 → 带称呼回复；陌生号码 → 通用回复（均走 `dmi:sms.send`）
- **错误处理**：`retry`（maxRetries=1，retryDelayMs=1000）

---

## 4. 依赖的系统能力桥与权限

| 桥工具名 | 实现 | 运行时权限 |
|---|---|---|
| `dmi.calendar.query` | [CalendarQueryBridge.kt](../packages/apps/GentonBrain/src/com/genton/brain/dmi/bridges/CalendarQueryBridge.kt) | `READ_CALENDAR` |
| `dmi.calendar.create` | [CalendarCreateBridge.kt](../packages/apps/GentonBrain/src/com/genton/brain/dmi/bridges/CalendarCreateBridge.kt) | `WRITE_CALENDAR` |
| `dmi.contacts.query` | [ContactsQueryBridge.kt](../packages/apps/GentonBrain/src/com/genton/brain/dmi/bridges/ContactsQueryBridge.kt) | `READ_CONTACTS` |
| `dmi.telephony.call` | [TelephonyCallBridge.kt](../packages/apps/GentonBrain/src/com/genton/brain/dmi/bridges/TelephonyCallBridge.kt) | `CALL_PHONE` |
| `dmi.sms.send` | [SmsSendBridge.kt](../packages/apps/GentonBrain/src/com/genton/brain/dmi/bridges/SmsSendBridge.kt) | `SEND_SMS` |
| `dmi.notification.notify` | [NotificationBridge.kt](../packages/apps/GentonBrain/src/com/genton/brain/dmi/bridges/NotificationBridge.kt) | `POST_NOTIFICATIONS`（Android 13+） |

Skill 中的 `system` 步骤（`service=notification`、`method=notify`）由编排引擎归一化为 `dmi.notification.notify` 后走同一套系统能力桥，见 [SkillExecutor.kt](../packages/apps/GentonBrain/src/com/genton/brain/skill/SkillExecutor.kt)。

**演示前须授予运行时权限**（上述 6 项已在 [AndroidManifest.xml](../packages/apps/GentonBrain/AndroidManifest.xml) 声明，仍需真机授予）：

```bash
adb shell pm grant com.genton.brain android.permission.READ_CALENDAR
adb shell pm grant com.genton.brain android.permission.WRITE_CALENDAR
adb shell pm grant com.genton.brain android.permission.READ_CONTACTS
adb shell pm grant com.genton.brain android.permission.CALL_PHONE
adb shell pm grant com.genton.brain android.permission.SEND_SMS
adb shell pm grant com.genton.brain android.permission.POST_NOTIFICATIONS
```

---

## 5. A4 真机演示脚本建议

演示按"只读 → 有副作用 → 敏感操作"递进，覆盖 4 种编排能力与 3 个风险档位。

| 步骤 | Skill | 输入 | 预期观察 |
|---|---|---|---|
| S1 | `skill:calendar.query_agenda` | 当天 00:00 ~ 次日 00:00 时间戳（ms） | 免确认直连返回日程清单，无确认面板 |
| S2 | `skill:notification.send_scheduled` | 同 S1 时间窗 | 条件分支命中：弹出"日程提醒"通知，正文含日程条数 |
| S3 | `skill:notification.batch_remind` | 同 S1 时间窗、日程 ≥2 条 | 循环逐条通知，条数 = `min(日程数, 5)` |
| S4 | `skill:calendar.create_event` | `title=演示日程`、无冲突时间窗 | 无冲突分支：创建成功返回 `eventId` |
| S5 | `skill:calendar.create_event` | 同 S4，改用与已有日程重叠的时间窗 | 冲突分支：通知提示"时间冲突"，不创建 |
| S6 | `skill:telephony.call_contact` | `contactName=通讯录已有姓名` | 强制确认面板 → 确认后拨号 |
| S7 | `skill:telephony.missed_call_autoreply` | `phoneNumber=已知联系人号码`、`contactName` | 短信回复正文带称呼 |

**时间戳取值**：`startTime` 取当日 00:00 的 `System.currentTimeMillis()` 对齐值，`endTime = startTime + 86_400_000`；演示前先确保日历中存在 1~2 条当日日程（可用 S4 预置）。

**语义直连验证点**：S1/S2/S3 应由 `semanticTriggers` 命中后免渲染直接执行（目标延迟 ≤100ms）；S6 应在确认面板中携带 `executionMode=0` 与参数回填。

---

## 6. 开放项（待对齐，不阻塞本次交付）

| # | 事项 | 说明 | 关联 |
|---|---|---|---|
| O1 | 能力令牌命名对齐 | 资产声明使用 `agent:action:*`（与 skill/DMI schema、作者指南一致），动作注册表 [action-registry.v1.json](../registry/action-registry.v1.json) 的 `requiredCapability` 使用 `agent.manage_notifications`、`agent.telephony_control` 等；两套命名的映射与校验规则尚未建立，资产侧 `capability` 暂不参与注册表存在性校验 | A4.2 / A7.2 / F2.1 |
| O2 | macro / 嵌套 skill 步骤 | `executeMacroStep()`（待 F1.1 集成）、`executeSkillStep()`（嵌套 Skill）仍为占位返回码 9999；首批 6 个资产未使用这两类步骤 | F1.1 / F3.3 |
| O3 | 资产装载路径 | manifest 登记资产仓库为 `vendor/genton/assets/skills`，当前工作区存放于 `assets/skills/`；`SkillAssetManager` 读取 APK `assets/skills/`，需在装载集成时对齐 APK assets 打包路径 | A8 / H1 |
| O4 | 运行时权限预置 | 未建立 `default-permissions` 预置，真机演示依赖 `pm grant`（见 §4） | H1 / A4 |

---

**资产索引**：[assets/skills/](../assets/skills/)
