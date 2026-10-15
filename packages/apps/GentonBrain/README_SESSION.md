# B3.1/B3.2 会话服务实现说明

## 概述

本文档说明 B3.1（会话服务大脑侧）和 B3.2（流式与卡片推送）的实现细节。

## 已完成功能

### B3.1 会话服务（大脑侧）

**文件：** `AgentSessionService.kt`

**核心接口实现：**

1. **openSession**
   - 创建会话，生成唯一 sessionId
   - 验证 Agent 注册状态（通过 AgentRuntimeClient）
   - 支持 entryMode=NEW/RESUME（resume 在 A3 期增强）
   - 返回 SessionHandle

2. **submitUserTurn**（幂等）
   - 基于 turn.requestId 实现幂等性
   - 重复请求返回相同 turnId
   - 异步处理轮次（不阻塞调用方）
   - 委托给 TurnManager 进入规划器

3. **cancel**
   - 取消指定轮次
   - 清理流式状态（StreamCoordinator）
   - 通知监听器 onTurnCancelled

4. **endSession**
   - 清理会话状态
   - 移除监听器
   - 清理所有相关轮次的流式状态

**依赖关系：**
- B1.2 TurnManager（Turn 管理）
- B2.2 ToolLoop（工具循环，通过 TurnManager）
- A4.1 AgentRuntimeClient（ACB 状态查询）
- StreamCoordinator（流式协调）

### B3.2 流式与卡片推送

**文件：**
- `StreamingPushManager.kt` - 推送管理器
- `StreamCoordinator.kt` - 流式协调器
- `TurnPushAdapter.kt` - Turn 状态适配器

**核心功能：**

1. **任务卡推送**（type=0）
   - 状态对齐 ACB：IDLE/PLANNING/EXECUTING/SUSPENDED/TERMINATED
   - 包含目标摘要（已脱敏）、进度、预算警告
   - `pushTaskCardChanged()`

2. **动作卡推送**（type=1–4）
   - 状态：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
   - 包含动作名称、目标应用、错误信息
   - `pushActionCardChanged()`

3. **流式文本推送**
   - **节流窗口**：100ms（避免过于频繁的 Binder 调用）
   - **thinking/tool_activity**：立即推送（type=0/2）
   - **text_stream**：合帧推送（type=1）
   - 轮次结束时强制刷新缓冲区

### A11.1 会话桥协调器

**文件：** `SessionBridgeCoordinator.kt`

**核心职责：**

1. **双向绑定管理**
   - 大脑提供 IAgentSessionService（SystemUI 绑定）
   - SystemUI 反向注册 IAgentAssistantUi
   - 权限要求：BIND_AGENT_UI（signature|privileged）

2. **主动绑定**（备用方案）
   - 大脑可主动绑定 SystemUI 的 UI 服务
   - 用于系统启动时预绑定
   - 超时保护：5 秒

3. **可用性检查**
   - `isAssistantUiAvailable()` - 检查 Binder 存活

## 架构设计

```
SystemUI 侧                          大脑侧
┌────────────────┐                  ┌────────────────────┐
│ SessionUiView  │                  │ AgentSessionService│
│                │                  │                    │
│ 1. bindService()────────────────>│ 2. 返回 Binder     │
│                │                  │                    │
│ 3. registerUi()─────────────────>│ 4. 保存 UI 引用    │
│                │<─────────────────│ 5. 推送状态/卡片   │
└────────────────┘                  └────────────────────┘
         │                                    │
         │                                    │
         v                                    v
  IAgentAssistantUi                  IAgentSessionService
  (UI 服务接口)                       (会话服务接口)
```

## 流式推送流程

```
用户输入
   │
   v
submitUserTurn() ──> TurnManager.submitTurn()
   │                        │
   │                        v
   │                   规划器（B2.1）
   │                        │
   │                        v
   │                   工具循环（B2.2）
   │                        │
   └────> StreamingPushManager
              │
              ├─> pushTaskCardChanged()      (任务卡状态)
              ├─> pushActionCardChanged()    (动作卡状态)
              └─> pushTurnDelta()            (文本流式)
                       │
                       v
                 StreamCoordinator
                       │
                       ├─> thinking: 立即推送
                       ├─> tool_activity: 立即推送
                       └─> text_stream: 合帧推送（100ms 节流）
                                │
                                v
                          IAgentSessionListener.onTurnDelta()
```

## 幂等性保证

**问题：** 网络抖动或 UI 重试可能导致重复提交

**解决方案：**

```kotlin
// 1. 客户端生成唯一 requestId
val turn = UserTurn().apply {
    requestId = UUID.randomUUID().toString()  // 客户端生成
    text = "帮我订机票"
}

// 2. 服务端幂等性检查
if (turn.requestId != null && sessionInfo.processedTurns.contains(turn.requestId)) {
    // 重复请求，返回相同 turnId
    return sessionInfo.turnIdByRequestId[turn.requestId]
}

// 3. 记录映射关系
sessionInfo.processedTurns.add(turn.requestId)
sessionInfo.turnIdByRequestId[turn.requestId] = turnId
```

**效果：** 同一 requestId 重复提交，始终返回相同 turnId

## entryMode=resume 恢复机制（A3 期增强）

**当前状态：** 占位实现，A3 期完成

**设计思路：**

1. **checkpoint 关联**（依赖 A4.3）
   - 会话 sessionId 关联 ACB checkpoint packId
   - 保存工作记忆快照

2. **上下文打包**（依赖 C3.3）
   - packContext 子图半径/Token 预算裁剪
   - 实体关系图预打包

3. **恢复流程**
   ```kotlin
   if (request.entryMode == ENTRY_MODE_RESUME) {
       // 1. 从 checkpoint 恢复 ACB 状态
       val checkpoint = runtimeClient.loadCheckpoint(request.checkpointId)
       
       // 2. 恢复工作记忆（实体子图）
       val contextPack = memoryService.loadContextPack(checkpoint.packId)
       
       // 3. 热切换延迟 ≤300ms（A4.3 要求）
       // ...
   }
   ```

## 与 D12 UI 侧对接

**SystemUI 侧需要实现：**

1. **绑定大脑会话服务**
   ```kotlin
   // SystemUI 侧代码（示例）
   val intent = Intent().apply {
       component = ComponentName(
           "com.genton.brain",
           "com.genton.brain.session.AgentSessionService"
       )
   }
   bindService(intent, connection, BIND_AUTO_CREATE)
   ```

2. **实现 IAgentSessionListener**
   ```kotlin
   val listener = object : IAgentSessionListener.Stub() {
       override fun onTurnStarted(handle: SessionHandle, turnId: String) {
           // 显示 "思考中" 指示器
       }
       
       override fun onTurnDelta(handle: SessionHandle, turnId: String, delta: TurnDelta) {
           // 流式更新文本
           when (delta.type) {
               0 -> showThinking()
               1 -> appendText(delta.text)
               2 -> showToolActivity(delta.toolKind)
           }
       }
       
       override fun onTaskCardChanged(handle: SessionHandle, card: TaskCardData) {
           // 更新任务卡 UI
       }
       
       override fun onActionCardChanged(handle: SessionHandle, card: ActionCardData) {
           // 更新动作卡 UI
       }
       
       override fun onTurnCompleted(handle: SessionHandle, result: TurnResultSummary) {
           // 显示完成状态
       }
   }
   ```

3. **反向注册 UI 服务**
   ```kotlin
   // 绑定成功后
   val sessionService = IAgentSessionService.Stub.asInterface(binder)
   val bridgeCoordinator = ... // 从服务获取
   bridgeCoordinator.registerAssistantUi(uiServiceBinder)
   ```

## 性能优化

### 1. 异步处理
- submitUserTurn 立即返回 turnId，异步处理
- 使用 CachedThreadPool，避免阻塞调用方

### 2. 流式节流
- 文本流 100ms 合帧窗口
- thinking/tool_activity 立即推送（用户体验优先）
- 减少 Binder 调用频率

### 3. 内存管理
- 会话结束自动清理 StreamCoordinator 状态
- 使用 ConcurrentHashMap 支持并发访问

## 安全考虑

### 1. 权限控制
- BIND_AGENT_UI：signature|privileged
- 只有 SystemUI（平台签名）可绑定

### 2. 数据脱敏
- goalSummaryMasked：目标摘要已脱敏
- TurnPushAdapter 中实现简单掩码（生产需接入 C4.3 PII 掩码模型）

### 3. Binder 异常处理
- 所有 listener 回调都包含 try-catch
- Binder 死亡不影响大脑服务稳定性

## 测试建议

### 单元测试

1. **幂等性测试**
   ```kotlin
   @Test
   fun testSubmitTurnIdempotency() {
       val turn = UserTurn().apply {
           requestId = "test-request-1"
           text = "test"
       }
       
       val turnId1 = service.submitUserTurn(handle, turn)
       val turnId2 = service.submitUserTurn(handle, turn)
       
       assertEquals(turnId1, turnId2)  // 相同 turnId
   }
   ```

2. **流式节流测试**
   ```kotlin
   @Test
   fun testStreamThrottling() {
       // 快速推送 10 次文本
       repeat(10) {
           coordinator.pushDelta(listener, handle, turnId, textDelta)
       }
       
       // 验证合帧：实际推送次数应 < 10
       verify(listener, atMost(3)).onTurnDelta(any(), any(), any())
   }
   ```

### 集成测试

1. **SystemUI 绑定测试**
   - 验证 BIND_AGENT_UI 权限检查
   - 验证反向注册成功

2. **端到端测试**
   - 提交用户输入 → 规划 → 执行 → 卡片推送 → UI 更新
   - 验证延迟：命令→可见 ≤100ms（P95）

## 里程碑对照

| 任务 | 状态 | 验收标准 |
|------|------|---------|
| B3.1 openSession | ✅ 完成 | 支持 NEW 模式，RESUME 占位 |
| B3.1 submitUserTurn 幂等 | ✅ 完成 | requestId 重复返回相同 turnId |
| B3.1 cancel | ✅ 完成 | 取消 + 清理流式状态 |
| B3.1 endSession | ✅ 完成 | 清理会话和监听器 |
| B3.2 流式推送 | ✅ 完成 | 100ms 节流窗口 |
| B3.2 任务卡推送 | ✅ 完成 | 状态对齐 ACB |
| B3.2 动作卡推送 | ✅ 完成 | 5 态状态机 |
| A11.1 会话桥 | ✅ 完成 | 双向绑定 + 反向注册 |
| A11.2 确认宿主 | ⏸️ 已实现 | ConfirmationUiRequest 下发（已在 A11.2 完成） |

## 下一步计划

### A3 期增强（依赖 C3.3）
- [ ] entryMode=RESUME 恢复工作记忆
- [ ] checkpoint 关联与热切换 ≤300ms
- [ ] packContext 子图打包执行

### 集成对接
- [ ] D2.2 SystemUI 侧会话状态消费实现
- [ ] F2.1 表单提交免 LLM 路由验证
- [ ] C4.3 PII 掩码模型接入（替换简单掩码）

## 参考文档

- [轨道A-D12-会话与推送桥](../../docs/D12-会话与推送桥.md)
- [WBS B3.1 任务说明](../../系统级开发项目WBS.md#3-部门-b大脑与-ai-runtime-部wbs-b)
- [A11.1 快速参考](../../docs/A11.1_快速参考.md)
