# Skill 作者指南

> AI Native OS Skill 编排模板开发指南  
> 版本：v1.0 | 更新日期：2026-09-10

## 目录

1. [Skill 快速入门](#1-skill-快速入门)
2. [编排模式详解](#2-编排模式详解)
3. [步骤类型使用](#3-步骤类型使用)
4. [条件判定语法](#4-条件判定语法)
5. [JSONPath 引用](#5-jsonpath-引用)
6. [错误处理与回退](#6-错误处理与回退)
7. [完整示例集](#7-完整示例集)
8. [最佳实践](#8-最佳实践)
9. [常见问题](#9-常见问题)

---

## 1. Skill 快速入门

### 1.1 什么是 Skill？

**Skill**（技能）是 AI Native OS 中的系统级能力封装单元，用于**组合多个 DMI/Macro/系统操作**形成复杂的自动化流程。

**核心特点：**
- 支持 4 种执行模式（sequential/parallel/conditional/fallback）
- 可组合 DMI、Macro、其他 Skill、系统服务
- 内置条件判定、循环、错误重试机制
- 通过 JSONPath 实现步骤间数据传递

### 1.2 Skill vs DMI vs Macro

| 维度 | **Skill** | **DMI** | **Macro** |
|------|-----------|---------|-----------|
| **定位** | 系统级能力编排 | 声明式接口 | 用户宏 |
| **复杂度** | 高（多步骤组合） | 中（单操作） | 低（简单序列） |
| **条件判定** | ✅ 支持 | ❌ 不支持 | ✅ 支持 |
| **执行模式** | 4 种 | 单一 | 单一 |
| **典型场景** | 智能来电处理、批量媒体控制 | 发送通知、查询日历 | 快捷指令 |

### 1.3 编写第一个 Skill

**示例：发送预定通知**

```json
{
  "skill": {
    "id": "skill:notification.send_scheduled",
    "name": "send_scheduled_notification",
    "version": "1.0.0",
    "category": "notification",
    "description": "查询日历事件并发送通知",
    "publisher": "android.system"
  },
  "metadata": {
    "capability": "agent:action:notify",
    "riskLevel": 1,
    "confirmation": "default",
    "requiredCapabilities": ["agent:action:notify", "agent:action:calendar_read"]
  },
  "execution": {
    "mode": "sequential",
    "steps": [
      {
        "id": "query_calendar",
        "type": "dmi",
        "config": {
          "dmiId": "dmi:calendar.query",
          "operation": "queryEvents",
          "parameters": {
            "startTime": "$.input.startTime",
            "endTime": "$.input.endTime"
          }
        }
      }
    ]
  }
}
```

**关键字段说明：**
- `skill.id`: 必须使用 `skill:` 前缀（如 `skill:notification.send_scheduled`）
- `metadata.riskLevel`: 整数 0/1/2（0=无副作用，1=一般副作用，2=敏感操作）
- `execution.mode`: 执行模式（sequential/parallel/conditional/fallback）
- `steps[].config.dmiId`: DMI 资产 ID，必须使用 `dmi:` 前缀

---

## 2. 编排模式详解

### 2.1 Sequential（顺序执行）

**用途：**步骤间有依赖关系，需按顺序执行。

**特点：**
- 严格按步骤顺序执行
- 前一步失败则中断（可配置）
- 支持步骤间数据传递

**示例：**

```json
{
  "execution": {
    "mode": "sequential",
    "steps": [
      {
        "id": "step1",
        "type": "dmi",
        "config": {
          "dmiId": "dmi:calendar.query",
          "operation": "queryEvents",
          "parameters": {"startTime": "$.input.time"}
        }
      },
      {
        "id": "step2",
        "type": "system",
        "config": {
          "service": "notification",
          "method": "notify",
          "parameters": {"title": "找到 {{$.step.step1.data.events.length}} 个日程"}
        }
      }
    ]
  }
}
```

### 2.2 Parallel（并行执行）

**用途：**多个独立操作，可同时执行以提高效率。

**特点：**
- 所有步骤同时启动
- 等待所有步骤完成后聚合结果
- 支持部分失败继续（配置 `errorHandling.onStepFailure: "continue"`）

**示例：**

```json
{
  "execution": {
    "mode": "parallel",
    "steps": [
      {
        "id": "pause_music",
        "type": "dmi",
        "config": {
          "dmiId": "dmi:media.control",
          "operation": "pause",
          "parameters": {"sessionId": "$.input.sessionId"}
        }
      },
      {
        "id": "lower_volume",
        "type": "system",
        "config": {
          "service": "media",
          "method": "setVolume",
          "parameters": {"stream": "STREAM_MUSIC", "level": 3}
        }
      }
    ],
    "errorHandling": {
      "onStepFailure": "continue"
    },
    "resultAggregation": {
      "strategy": "all_results"
    }
  }
}
```

### 2.3 Conditional（条件分支）

**用途：**根据运行时条件选择不同执行路径。

**特点：**
- 通过 `condition` 步骤定义分支逻辑
- 支持 `then`/`else` 两个分支
- 可嵌套多层条件

**示例：**

```json
{
  "execution": {
    "mode": "sequential",
    "steps": [
      {
        "id": "check_vip",
        "type": "condition",
        "config": {
          "condition": {
            "type": "contains",
            "left": "$.step.check_contact.data.contact.groups",
            "right": "VIP"
          },
          "then": [
            {
              "id": "accept_call",
              "type": "dmi",
              "config": {
                "dmiId": "dmi:telephony.control",
                "operation": "answer",
                "parameters": {}
              }
            }
          ],
          "else": [
            {
              "id": "reject_call",
              "type": "dmi",
              "config": {
                "dmiId": "dmi:telephony.control",
                "operation": "reject",
                "parameters": {}
              }
            }
          ]
        }
      }
    ]
  }
}
```

### 2.4 Fallback（回退链）

**用途：**主步骤失败时，依次尝试备选方案。

**特点：**
- 按步骤顺序尝试，遇到第一个成功则停止
- 适合多种数据源或服务降级场景

**示例：**

```json
{
  "execution": {
    "mode": "fallback",
    "steps": [
      {
        "id": "primary_service",
        "type": "dmi",
        "config": {
          "dmiId": "dmi:weather.primary",
          "operation": "query",
          "parameters": {"location": "$.input.location"}
        }
      },
      {
        "id": "backup_service",
        "type": "dmi",
        "config": {
          "dmiId": "dmi:weather.backup",
          "operation": "query",
          "parameters": {"location": "$.input.location"}
        }
      }
    ],
    "resultAggregation": {
      "strategy": "first_success"
    }
  }
}
```

---

## 3. 步骤类型使用

### 3.1 DMI 步骤（type: "dmi"）

**用途：**调用声明式接口操作。

**必填字段：**
- `dmiId`: DMI 资产 ID（必须以 `dmi:` 开头）
- `operation`: 操作名称（对应 DMI 的 operationId）
- `parameters`: 操作参数（支持 JSONPath 引用）

**示例：**

```json
{
  "id": "send_notification",
  "type": "dmi",
  "config": {
    "dmiId": "dmi:notification.send",
    "operation": "notify",
    "parameters": {
      "title": "提醒",
      "body": "$.step.query_calendar.data.events[0].title",
      "priority": "high"
    },
    "timeout": 5000
  },
  "outputMapping": {
    "notificationId": "$.data.id"
  }
}
```

### 3.2 Macro 步骤（type: "macro"）

**用途：**调用用户自定义宏。

**必填字段：**
- `macroId`: Macro 资产 ID（必须以 `macro:` 开头）
- `parameters`: 宏参数

**示例：**

```json
{
  "id": "run_custom_macro",
  "type": "macro",
  "config": {
    "macroId": "macro:user.morning_routine",
    "parameters": {
      "wakeupTime": "$.input.time"
    }
  }
}
```

### 3.3 Skill 步骤（type: "skill"）

**用途：**嵌套调用其他 Skill（支持递归组合）。

**必填字段：**
- `skillId`: 目标 Skill ID（必须以 `skill:` 开头）
- `parameters`: Skill 参数

**示例：**

```json
{
  "id": "sub_skill",
  "type": "skill",
  "config": {
    "skillId": "skill:notification.simple_send",
    "parameters": {
      "message": "$.input.message"
    }
  }
}
```

### 3.4 System 步骤（type: "system"）

**用途：**调用系统级操作（音量控制、场景查询等）。

**必填字段：**
- `service`: 系统服务名（notification/calendar/contacts/telephony/settings/media/location）
- `method`: 服务方法名
- `parameters`: 方法参数

**示例：**

```json
{
  "id": "set_volume",
  "type": "system",
  "config": {
    "service": "media",
    "method": "setVolume",
    "parameters": {
      "stream": "STREAM_MUSIC",
      "level": 5
    }
  }
}
```

### 3.5 Condition 步骤（type: "condition"）

**用途：**定义条件分支。

**必填字段：**
- `condition`: 条件判定表达式
- `then`: true 分支步骤数组
- `else`: false 分支步骤数组（可选）

**示例：**

```json
{
  "id": "check_dnd",
  "type": "condition",
  "config": {
    "condition": {
      "type": "equals",
      "left": "$.step.get_scene.data.dndActive",
      "right": true
    },
    "then": [
      {
        "id": "send_voicemail",
        "type": "dmi",
        "config": {
          "dmiId": "dmi:telephony.control",
          "operation": "rejectWithVoicemail",
          "parameters": {}
        }
      }
    ],
    "else": [
      {
        "id": "ring",
        "type": "system",
        "config": {
          "service": "telephony",
          "method": "ring",
          "parameters": {}
        }
      }
    ]
  }
}
```

### 3.6 Loop 步骤（type: "loop"）

**用途：**遍历数组或重复执行操作。

**必填字段：**
- `iterateOver`: 数据源（JSONPath 数组）
- `itemVariable`: 循环项变量名（默认 "item"）
- `steps`: 循环体步骤数组
- `maxIterations`: 最大迭代次数（防止死循环）

**示例：**

```json
{
  "id": "notify_all_contacts",
  "type": "loop",
  "config": {
    "iterateOver": "$.step.query_contacts.data.contacts",
    "itemVariable": "contact",
    "indexVariable": "index",
    "maxIterations": 100,
    "steps": [
      {
        "id": "send_message",
        "type": "dmi",
        "config": {
          "dmiId": "dmi:sms.send",
          "operation": "sendText",
          "parameters": {
            "to": "$.contact.phoneNumber",
            "body": "$.input.message"
          }
        }
      }
    ]
  }
}
```

---

## 4. 条件判定语法

### 4.1 支持的条件类型

| 类型 | 描述 | 字段 |
|------|------|------|
| `equals` | 相等判断 | `left`, `right` |
| `not_equals` | 不等判断 | `left`, `right` |
| `greater_than` | 大于 | `left`, `right` |
| `less_than` | 小于 | `left`, `right` |
| `contains` | 包含（数组/字符串） | `left`, `right` |
| `exists` | 字段存在性 | `left` |
| `and` | 逻辑与 | `conditions` |
| `or` | 逻辑或 | `conditions` |
| `not` | 逻辑非 | `conditions` |

### 4.2 基础条件

**equals 示例：**

```json
{
  "type": "equals",
  "left": "$.step.check_contact.data.found",
  "right": true
}
```

**contains 示例：**

```json
{
  "type": "contains",
  "left": "$.step.check_contact.data.contact.groups",
  "right": "VIP"
}
```

**exists 示例：**

```json
{
  "type": "exists",
  "left": "$.step.query_calendar.data.events[0]"
}
```

### 4.3 复杂条件组合

**示例：VIP 联系人或驾驶场景**

```json
{
  "type": "or",
  "conditions": [
    {
      "type": "and",
      "conditions": [
        {
          "type": "equals",
          "left": "$.step.check_contact.data.found",
          "right": true
        },
        {
          "type": "contains",
          "left": "$.step.check_contact.data.contact.groups",
          "right": "VIP"
        }
      ]
    },
    {
      "type": "equals",
      "left": "$.step.get_scene.data.scene",
      "right": "driving"
    }
  ]
}
```

**等价逻辑：**
```
(found == true && contact.groups.contains("VIP")) || scene == "driving"
```

---

## 5. JSONPath 引用

### 5.1 语法规则

AI Native OS 使用**简化 JSONPath** 实现步骤间数据传递：

| 引用路径 | 描述 | 示例 |
|---------|------|------|
| `$.input.xxx` | Skill 输入参数 | `$.input.startTime` |
| `$.step.step_id.xxx` | 步骤执行结果 | `$.step.query_calendar.data.events` |
| `$.step.step_id.data.xxx` | 步骤返回数据 | `$.step.query_calendar.data.events[0].title` |

**注意：**
- 使用 `.` 访问对象字段
- 使用 `[index]` 访问数组元素
- 不支持复杂过滤器（如 `$[?(@.price < 10)]`）

### 5.2 模板变量

除了 `$.` 语法，还支持 `{{}}` 模板语法：

```json
{
  "parameters": {
    "body": "您有 {{$.step.query_calendar.data.events.length}} 个会议"
  }
}
```

### 5.3 实战示例

**场景：**查询日历后发送通知，通知内容包含会议标题。

```json
{
  "execution": {
    "mode": "sequential",
    "steps": [
      {
        "id": "query_calendar",
        "type": "dmi",
        "config": {
          "dmiId": "dmi:calendar.query",
          "operation": "queryEvents",
          "parameters": {
            "startTime": "$.input.startTime"
          }
        }
      },
      {
        "id": "send_notification",
        "type": "system",
        "config": {
          "service": "notification",
          "method": "notify",
          "parameters": {
            "title": "即将到来的会议",
            "body": "{{$.step.query_calendar.data.events[0].title}}"
          }
        }
      }
    ]
  }
}
```

---

## 6. 错误处理与回退

### 6.1 errorHandling 配置

```json
{
  "execution": {
    "errorHandling": {
      "onStepFailure": "abort",
      "maxRetries": 3,
      "retryDelayMs": 1000
    }
  }
}
```

| 策略 | 行为 |
|------|------|
| `abort` | 立即中止整个 Skill 执行 |
| `continue` | 跳过失败步骤，继续执行后续步骤 |
| `retry` | 重试当前步骤（最多 `maxRetries` 次） |
| `fallback` | 尝试 `fallbackChain` 中的备选方案 |

### 6.2 fallbackChain 声明

**顶层字段：**在 Skill 根级别定义降级链。

```json
{
  "skill": {...},
  "execution": {...},
  "fallbackChain": [
    "skill:notification.simple_send",
    "system:showToast"
  ]
}
```

**格式：** `type:assetId`（type 可以是 skill/dmi/macro/system）

---

## 7. 完整示例集

### 7.1 循环编排：逐条提醒

**场景：**查询当日日程后，逐条推送提醒通知。

完整示例见：[notification_batch_remind.json](../assets/skills/notification_batch_remind.json)

**关键要点：**
- `loop.iterateOver: "$.step.query_calendar.data.events"` - 迭代上一步查询结果
- `itemVariable: "event"` + 模板 `{{$.event.title}}` - 循环变量内插
- `errorHandling.onStepFailure: "continue"` - 单条失败不中断

### 7.2 条件分支：冲突检测后创建日程

**场景：**创建日程前先查询同时间段安排，有冲突则通知提示，无冲突则创建。

完整示例见：[calendar_create_event.json](../assets/skills/calendar_create_event.json)

**关键要点：**
- `condition` 步骤的 `then` / `else` 分支各自执行通知或创建
- 条件左值用 JSONPath 数组长度：`$.step.check_conflict.data.events.length`
- `riskLevel: 1` - 写日历（一般副作用）

### 7.3 顺序执行：发送预定通知

**场景：**查询日历事件，根据结果发送不同通知。

完整示例见：[send_scheduled_notification.json](../assets/skills/send_scheduled_notification.json)

**关键要点：**
- `mode: "sequential"` - 必须先查询日历再发送通知
- `condition.type: "greater_than"` - 判断是否有日程
- `then` / `else` 分支处理不同情况

---

## 8. 最佳实践

### 8.1 命名规范

- **Skill ID**：`skill:domain.action`（如 `skill:notification.send_scheduled`）
- **步骤 ID**：小写下划线（如 `query_calendar`、`send_notification`）
- **参数名**：驼峰命名（如 `startTime`、`phoneNumber`）

### 8.2 风险等级选择

| 级别 | 场景 | 示例 |
|------|------|------|
| **0** | 无副作用操作 | 查询数据、读取设置 |
| **1** | 一般副作用 | 发送通知、调整音量 |
| **2** | 敏感操作 | 接听/拒接电话、发送消息、支付 |

### 8.3 性能优化

1. **优先使用 Parallel 模式**：独立操作并行执行可大幅提升效率
2. **限制循环次数**：`maxIterations` 必须设置，防止死循环
3. **设置超时时间**：`execution.timeout` 避免长时间阻塞
4. **合理聚合结果**：
   - `first_success`：仅关心是否有成功
   - `all_results`：需要完整数据
   - `merge`：自动合并对象字段

### 8.4 安全建议

- **最小权限原则**：仅声明必需的 `requiredCapabilities`
- **输入校验**：使用 JSON Schema 验证参数格式
- **敏感操作确认**：`riskLevel: 2` 的 Skill 应设置 `confirmation: "always"`
- **降级链设计**：关键 Skill 必须提供 `fallbackChain`

---

## 9. 常见问题

### Q1: Skill 与 Macro 如何选择？

**A:** 
- **Skill**：系统级能力，需要复杂编排、条件判定、多数据源聚合
- **Macro**：用户快捷指令，简单线性流程

### Q2: 为什么我的条件判定总是失败？

**A:** 检查以下几点：
1. JSONPath 路径是否正确（使用 `$.step.step_id.data.field`）
2. 字段类型是否匹配（数字比较需要两边都是 Number）
3. 使用 `exists` 先判断字段是否存在

### Q3: 如何调试 Skill？

**A:**
1. 使用 `SkillAssetManager.loadSkillFromFile()` 加载本地文件
2. 查看 `SkillExecutionResult` 中的 `errorCode` 和 `errorMessage`
3. 启用系统日志：`adb logcat | grep SkillExecutor`

### Q4: 步骤失败后如何获取错误信息？

**A:** 执行结果中包含完整错误详情：

```kotlin
val result = skillAssetManager.executeSkill("skill:xxx", params)
if (!result.success) {
    Log.e(TAG, "Error: ${result.errorCode} - ${result.errorMessage}")
}
```

### Q5: 可以嵌套多少层 Skill？

**A:** 理论上无限制，但建议不超过 **3 层**，避免调试困难和性能问题。

### Q6: 如何在循环中访问当前项？

**A:** 使用 `itemVariable` 和 `indexVariable`：

```json
{
  "type": "loop",
  "config": {
    "iterateOver": "$.step.query_contacts.data.contacts",
    "itemVariable": "contact",
    "steps": [
      {
        "type": "dmi",
        "config": {
          "parameters": {
            "phoneNumber": "$.contact.phoneNumber"
          }
        }
      }
    ]
  }
}
```

---

## 附录

### A. 完整 Schema 定义

参见：[skill-asset-schema.json](schemas/skill-asset-schema.json)

### B. 示例代码仓库

- [发送预定通知（顺序 + 条件）](../assets/skills/send_scheduled_notification.json)
- [逐条提醒（循环）](../assets/skills/notification_batch_remind.json)
- [冲突检测建日程（条件）](../assets/skills/calendar_create_event.json)
- [日程只读查询](../assets/skills/calendar_query_agenda.json)
- [联系人拨号（敏感操作）](../assets/skills/telephony_call_contact.json)
- [未接来电自动回复](../assets/skills/telephony_missed_call_autoreply.json)

### C. 相关文档

- [DMI 作者指南](DMI_AUTHOR_GUIDE.md)
- [轨道 A 技能与宏库服务接口草案](schemas/skill-asset-schema.json)

---

**文档维护：** AI Native OS Team  
**反馈渠道：** issues@gentonos.dev
