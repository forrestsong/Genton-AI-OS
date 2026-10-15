# AINativeBrain 契约修复清单

**目标**：消除 brain 应用的**接口/类型契约缺口**，使其可编译。
**结果**：编译错误 **470 → 358**（−112，−24%），**契约类错误全部清零**。

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 总错误 | 470 | **358** |
| `overrides nothing`（AIDL 契约不匹配） | 6 | **0** ✅ |
| `redeclaration`（类型重复声明） | 26 | **0** ✅ |
| `unresolved reference` | 341 | 279 |
| `no parameter with name` | 30 | 15 |

---

## 一、AIDL 契约不匹配（6 → 0）

`AgentSessionService` 声明 `override` 的方法与 AIDL 契约不符，Kotlin 报 `overrides nothing`。

**用 `javap` 从 `framework-minus-apex` 的 `.class` 反编译得到权威签名**：

```java
int openSession(SessionOpenRequest, IAgentSessionListener, SessionHandle)   // 注意：非数组！
int submitUserTurn(SessionHandle, UserTurn, String[])
int cancelTurn(SessionHandle, String)
int endSession(SessionHandle, int)
```

**关键坑**：AIDL 的 `out` 参数——
- **parcelable** 类型 → 直接传对象（可变），**不是数组**
- **String / 基本类型** → 传数组（`String[]`）

**修复**（`session/AgentSessionService.kt`）：原实现改名为私有 `*Impl`，另加薄 `override` 适配层：

| 方法 | 原实现 | AIDL 适配层 |
|---|---|---|
| `openSession` | `SessionHandle?` | `Int` + `handle.sessionId/ownerAgentId` 字段回填 |
| `submitUserTurn` | `String?` | `Int` + `turnId[0]` 写入 |
| `cancel` | `Boolean` | **重命名** `cancelTurn`，返回 `Int` |
| `endSession` | `(handle)` | **补 `reason: Int`**，返回 `Int` |

状态码用 AIDL 常量：`STATUS_OK=0` / `STATUS_SESSION_NOT_FOUND=2` / `STATUS_INTERNAL_ERROR=-1`。

**另修**：`ui/form/FormCardView.kt` 声明了 `override fun onStateChanged` 但类未实现接口 `FormCardStateListener` → 补上接口声明。

**同源修复**（前一轮）：`runtime/AgentRuntimeClient.kt` 补 `executeAction` / `queryActionStatus` / 嵌套 `ActionStatus`（原缺失，走 `ServiceManager` 真实调用 `IAgentActionService`）。

---

## 二、类型重复声明（26 → 0）

**三组文件重复声明了同名类型**，导致 `redeclaration` 及连锁的「找不到命名参数」错误。
判据：**看哪一版的独有成员被实际引用**。

| 重复类型 | 文件 A（废弃） | 文件 B（保留） | 判据 |
|---|---|---|---|
| `GoalNode` / `GoalStatus` / `GoalDAG` | `planner/GoalModels.kt` | `planner/PlanningDataTypes.kt` | A 独有成员（`actions`/`edges`/`PENDING`）**零引用**；B 独有成员（`parentGoalId`×13、`rootGoalId`×4、`PLANNED`、`depth`）大量使用 |
| `ActionPlan` 等 **9 个类型** | `toolloop/ToolLoopModels.kt` | `toolloop/ToolLoopDataTypes.kt` | A 无独有成员；B 独有（`completedGoals`×10、`budgetUsage`×22、`toJson`×24）大量使用 |
| `ToolExecutionResult` | `session/FormRouter.kt` | `session/ActionCardAdapter.kt` | B 是**超集**（多 `needsUserConfirmation`/`confirmationPrompt`，均有默认值，向后兼容） |

**处理**：废弃文件置空并保留说明注释（原文件备份为 `*.obsolete` / `*.bak`）。

---

## 三、包路径错误

`session/FormSubmitRouter.kt` 从 `com.ainative.brain.toolloop.GoalNode` 导入，但该类型实际在 `planner` 包 → 修正为 `com.ainative.brain.planner.GoalNode`。

---

## 四、缺失构建依赖

`ui/form/ControlRenderer.kt` 等使用 `com.google.android.material.textfield.TextInputLayout` 等，
但 `Android.bp` 的 `static_libs` 未声明 material → 补 `"com.google.android.material_material"`
（与 AOSP 的 Settings / Launcher3 / Contacts 用法一致）。此项单独消除约 50 个错误。

---

## 五、剩余工作（非契约问题）

剩余 **358 个错误**为**实现缺口**（非接口契约），主要是缺失的类/字段/方法：

| 符号 | 次数 | 性质 |
|---|---|---|
| `type` | 35 | 某类缺 `type` 属性 |
| `PiiMaskRules` / `PiiMaskService` | 17 / 4 | 缺失类 |
| `Builder` | 12 | 缺失 Builder |
| `cardStateManager` / `streamCoordinator` | 8 / 3 | 缺失字段 |
| `Interpreter` / `ExtractedFact` / `ExtractedPreference` | 6 / 4 / 4 | 缺失类型 |
| `pushActionCard` | 7 | 缺失方法 |

集中在：`ui/form/ControlRenderer.kt`(48)、`memory/security/PiiMaskIntegration.kt`(33)、
`session/AgentSessionServiceImpl.kt`(26)、`session/AgentSessionService.kt`(24)、
`prediction/metrics/PredictionMetricsAggregator.kt`(23)。

**结论**：契约层已打通；剩余属功能实现补全，是独立于本主线（W1–W9 AOSP 构建验证）的**应用开发工作量**。

---

## 附：验证方式

```bash
cd /root/aosp
source build/envsetup.sh && lunch sdk_phone64_x86_64-bp2a-userdebug
export SKIP_ABI_CHECKS=true
m -j6 AINativeBrain 2>&1 | grep -c ': error:'
```
