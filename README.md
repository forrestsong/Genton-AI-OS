# Genton AI OS — 一个开放的 AI 原生移动操作系统底座

> **命名由来**：Genton AI OS 的名字取自 **Genius Tonality**（智能之调）——在 AI 原生的操作系统中，智能体与系统、与用户、与其他服务之间，需要一种和谐有序的协作调性，正如音乐中不同声部在统一调性下协奏出完整的作品。
>
> **当前版本**：v0.3-dev（Android 深度定制轨道）　**状态**：核心模块开源中，持续迭代
>
> **项目定位**：面向基础模型、硬件厂商、软件服务商的**基于隐私审计的可信合作契约层**，解决智能体原生操作系统中「入口归属与主导权分离」的合作难题，构建跨主体互信协作的 AI 原生移动操作系统。
>
> [English Version](README.en.md)

---

## 设计理念：多方安全下的可信合作契约

智能体原生操作系统面临一个结构性矛盾：**入口归属与发展主导权高度绑定**。模型厂商、硬件厂商、软件服务商各有其核心资产与入口诉求，深度合作往往因信任边界不清而难以推进。

Genton AI OS 的核心思路是：**不做新的入口霸主，做各方都能信任的「契约层」**——在操作系统层面内置隐私审计、能力令牌、策略引擎与可验证审计链，让不同主体的智能体与服务能在同一台设备上协作，同时各方均能确信：

- 自身模型权重与核心能力不会被另一方获取；
- 用户数据的流向与使用范围可审计、可追溯；
- 能力与权限按任务最小化授予，不会被越界调用；
- 价值分配可基于透明的审计记录进行结算。

这就是「**基于隐私审计的可信合作契约**」。

### 契约层的四项核心机制

| 机制 | 说明 |
|---|---|
| **策略即代码** | 所有协作规则以策略 DSL 写入系统，编译期校验、运行时强制执行，任何一方无法单方面篡改 |
| **能力令牌化** | 权限不是「永久授予」，而是按任务、按时间窗口签发的能力令牌（Capability Token），可追踪、可撤销 |
| **审计可验证** | 所有跨主体调用记入哈希链 + TEE 锚定的审计日志，不可篡改、可独立验证，构成合作结算与责任界定的客观依据 |
| **隐私即边界** | 数据按最小必要原则流动，PII（个人可识别信息）自动掩码，敏感计算在隔离域完成——实现数据「可用不可见」 |

### 产品范式：设备是中继器，而不再是终端

设备成为人与数字世界之间的**中继器**：上行将用户意图翻译为执行中继，下行将执行结果端到端交回用户验收。应用退居后台成为能力供给方，用户不再被锁在操作过程中。

正因为设备是中继器，数据的流向、存储与操作**必须安全可审计**——只有这样，才能在**模型 — 设备 — 应用 — 用户**四方之间、在不同厂商之间、在人类与 AI 之间建立信任，系统才能持续良性演进。

```
旧模式：  用户 ──操作──▶ 手机（终端） ──调用──▶ App / 服务
                     ↑ 用户被锁在过程中

新模式：  用户（表达结果） ──▶ 手机（中继器） ──▶ 数字世界（执行）
              ◀────────────── 端到端送达结果 ◀──────────────┘
                         ↑
              模型/硬件/应用/用户 四方互信
              = 策略即代码 + 能力令牌 + 审计可验证
```

### 交互范式：HUI（Humanistic UI）—— 基于持续可学习决策模型的原生 AI 交互

Genton AI OS 不仅定义了可信合作的契约底座，也提出了面向原生 AI 硬件的全新交互范式 **HUI（Humanistic UI）**。它不是「在 GUI 上加一个聊天框」，而是以**持续可学习决策模型**为核心，重新定义人与设备的交互方式：

| 交互维度 | 传统 GUI | HUI |
|---|---|---|
| **核心输入通道** | 触屏点击 + 图标导航 | 自然语言 + 姿态手势 + 情境感知 |
| **系统响应模式** | 用户显式触发，系统被动执行 | 系统理解意图后自主规划执行，用户验收结果 |
| **主动性** | 被动等待用户指令 | 基于本地决策模型主动预判（通知整理、日程预准备、反骚扰等），始终可被用户打断 |
| **学习方式** | 用户手动设置与分类 | 基于决策模型的端侧持续学习，过程可审计、可回滚 |
| **授权方式** | 权限弹窗（一次性同意） | 能力令牌 + 可回溯审计链 + 随时可撤销 |
| **系统形态** | 固定单形态 | 双模式一键切换 —— AI 全能助理模式 ↔ 普通应用模式 |

HUI 的六大交互支柱：

1. **会话式主界面**：默认进入 AI 全能助理模式的会话式交互界面，所有系统能力以自然语言对话形式交付；传统应用模式为一键可切回的备选形态，零学习成本。
2. **建议胶囊（Suggestion Capsule）**：基于本地决策模型的情境预判，在合适时机以胶囊形态推送辅助建议（回复草稿、日程提醒、操作建议），不打断、不强制，用户可接受也可忽略。
3. **可视化确认流**：敏感操作（发消息、改系统设置、支付等）由系统级确认面板独占呈现（FLAG_SECURE 防截屏），用户可清晰确认操作主体、动作内容与影响范围，确认后才执行；确认结果直接回调执行中介，不经 Agent 运行时（以下简称「大脑」），防止劫持。
4. **活性指示器**：系统全局可见的 Agent 活动指示——当 Agent 在读取屏幕、执行操作、采集音频时，用户随时可感知。隐私仪表盘提供完整的审计时间线与链完整性验证入口。
5. **姿态交互（S7）**：将物理手势作为意图输入的补充通道——摇一摇就绪相机问答、翻面静置收声备忘。传感器上下文由系统 MotionHub 服务唯一消费，第三方不可读取；默认关闭，敏感场景强制停用，日均功耗增量 <0.5%。
6. **L0.5 表单直连**：高频确定性任务不走模型规划，而是通过 schema 派生表单 + 免大语言模型（LLM）确定性管线 + 语义直连完成。**延迟指标 ≤100ms（P95）来自架构设计文档，尚未在真机实测**。系统在使用中持续学习哪些任务可收敛为一键直达。

HUI 的设计原则：**主动而不越界，辅助而不打扰。** 所有主动能力遵循「默认保守、可一键全关、学习过程透明」的原则——决策模型只输出建议，最终裁决权永远在策略引擎（确定性规则）和用户手中。

### 技术路径：先立信任底座，再建协作生态

信任是合作的前提，也是智能体自主能力的前提。Genton AI OS 的技术路径不是「先做强、再补安全」，而是先建立可信底座，再逐步扩展协作能力：

```
第一阶段  信任底座（系统级、前置）
        ┌───────────────────────────────────┐
        │ 策略即代码：规则入系统、强制生效    │
        │ 能力令牌化：按任务最小化授予       │
        │ 审计可验证：哈希链 + TEE 锚定      │
        │ 隐私即边界：可用不可见、自动脱敏   │
        └───────────────────────────────────┘
                    │（在此边界内）
                    ▼
第二阶段  多方协作与智能演进
        ┌───────────────────────────────────┐
        │ 多 Agent 跨主体协作                │
        │ 模型/硬件/服务即插即用             │
        │ 基于审计的价值分配与结算           │
        │ 在可信边界内持续学习与演进         │
        └───────────────────────────────────┘
                    │
                    ▼
    信任越强 → 协作越顺畅 → 生态越繁荣
    → 用户体验越好 → 各方价值共赢
```

### 五大设计原则

| 原则 | 含义 |
|---|---|
| **AI-First（原生推理能力）** | 推理引擎是系统级常驻能力，而非某个应用的附属功能 |
| **Intent-Driven（意图驱动）** | 用户与 Agent 表达目标，系统解析为目标图（Goal DAG）→ 策略引擎验证 → 确定性执行器落地 |
| **多方安全（Multi-Party Security）** | 模型、硬件、服务、用户四方各有其权，任何一方不能越界；系统是中立的契约执行方 |
| **审计与可验证内建** | 策略即代码 + 哈希链审计 + TEE 锚定，所有合作行为可独立验证 |
| **确定性执行保障** | AI 输出是概率性的，但**系统副作用的执行必须是确定性的**——所有系统动作必经「意图总线 → 策略引擎 → 确定性执行器 → 审计」链路，防止模型输出直接产生系统副作用 |

---

## 技术架构

### 整体架构：双轨制，共享契约

Genton AI OS 不是又一个封闭的智能体 OS，而是一个**开放的契约层**：它定义了模型、硬件、服务、用户四方在一台设备上协作的规则与边界，各方按契约接入，按审计结算。

我们采用"双路线"架构实现这一契约层：近中期以 **Android 深度定制（轨道 A）** 为落地主线，长期以 **原生微内核（轨道 B）** 为目标研究线。两条路线共享同一套抽象契约与 Schema——这是"未来可迁移"的锚点，也是跨厂商互操作的基础。

```
┌────────────────────────── 共享契约层 ──────────────────────────┐
│  ACB / Intent / Action / Policy / Audit / Memory / ...         │
└────────┬───────────────────────────────────────────┬───────────┘
         │                                           │
         ▼                                           ▼
 轨道 A：Android 深度定制                    轨道 B：原生微内核
 ┌────────────────────────────┐             ┌────────────────────┐
 │  SystemUI 双模式 + 一键切换  │ 交互/界面  │  认知壳 · 语义文件  │
 │  Launcher3 (普通模式)       │             │  系统              │
 ├────────────────────────────┤             ├────────────────────┤
 │  Agent大脑(特权系统应用)    │ 运行时/Agent│ AI Runtime·技能库   │
 │  · AI Runtime · Skills     │             │ · 多Agent框架       │
 ├────────────────────────────┤             ├────────────────────┤
 │  system_server 服务集群    │ 系统服务    │ ACB·意图总线·记忆· │
 │  感知·执行·记忆·审计·策略  │ (同契约)    │ 上下文 (同契约)     │
 ├────────────────────────────┤             ├────────────────────┤
 │  SELinux + 平台签名        │ 安全/底层    │ Rust微内核·AI调度  │
 │  内核: 有限定制 / HAL       │             │ · KV内存管理        │
 └────────────────────────────┘             └────────────────────┘
  Android 16 生态 / 厂商 BSP                  NPU·统一内存·TEE
```

### 核心模块一览

以下为轨道 A（Android 深度定制）的已实现模块，全部位于本仓库中：

#### 安全与信任底座（策略 / 审计 / 令牌 / 鉴权）

| 模块 | 位置 | 状态 | 说明 |
|---|---|---|---|
| **策略引擎** | `overlay/frameworks/base/services/.../PolicyEngineService.java` | ✅ 核心完成 | 两阶段求值（意图级预检 + 动作级令牌签发）、DSL 编译、trie 索引、令牌签发/撤销、热更新；基线策略（baseline）优先级最高，不可被用户规则覆盖 |
| **审计服务** | `overlay/.../AuditService.java` | ✅ 核心完成 | 哈希链 + TEE 周期锚定、按角色脱敏查询、链完整性校验、活性流订阅 |
| **TEE 锚定管理** | `overlay/.../TeeAnchorManager.java` | ⚠️ 框架完成 | Android Keystore EC 签名；硬件 StrongBox 背书需真机验证 |
| **确认宿主服务** | `overlay/.../ConfirmationHostService.java` | ✅ 核心完成 | HMAC 验签、防重放、确认排队、超时自动拒绝、决策结果直接回调执行中介（不经 Agent 运行时）均已实现；⚠️ **面板展示桥接待接线**（SystemUI 面板视图已就绪，host→UI 调用当前为 TODO） |
| **Binder 单点鉴权** | `overlay/.../AgentCallerAuth.java` | ✅ 已实现 | 所有核心服务 onTransact 统一鉴权（WBS 1.3） |
| **能力令牌链** | `overlay/.../policy/token/` | ✅ 已实现 | Token 签名 / 校验 / 撤销列表三件套 |
| **PII 掩码引擎** | `overlay/.../PiiMaskingEngine.java` | ✅ 已实现 | 邮箱/手机/身份证/银行卡/凭据赋值检测，高风险场景「失败即拒绝」（fail-closed） |
| **SELinux 策略** | `overlay/system/sepolicy/private/` | ✅ 5 个域 | genton_brain / service / systemui / system_server / neverallow |

#### 动作执行系统（确定性执行保障）

| 模块 | 位置 | 状态 | 说明 |
|---|---|---|---|
| **执行中介** | `frameworks/base/services/.../AgentActionService.kt` | ✅ 高 | 8 态状态机、统一入口、策略→确认→执行→审计全流程编排 |
| **输入执行器** | `.../action/executor/InputExecutor.kt` | ✅ 高 | 点击/长按/滑动/输入/按键，带安全窗口检测 |
| **Activity 执行器** | `.../action/executor/ActivityExecutor.kt` | ✅ 高 | 启动应用/切换前台 |
| **设备控制执行器** | `.../action/executor/DeviceControlExecutor.kt` | ✅ 高 | 音量/亮度/系统设置面板 |
| **通知执行器** | `.../action/executor/NotificationExecutor.kt` | ✅ 中高 | 清除/回复通知 |
| **剪贴板执行器** | `.../action/executor/ClipboardExecutor.kt` | ✅ 中高 | 设置剪贴板内容，带污染源（taint）校验 |
| **内部动作执行器** | `.../action/executor/InternalActionExecutor.kt` | ✅ 高 | sys./policy./cmd. 内部动作白名单路由 |
| **Taint 污染源校验** | `.../action/TaintRuleInitializer.kt` | ✅ 中高 | 参数来源信任等级校验，「失败即拒绝」（fail-closed） |
| **动作注册表** | `registry/action-registry.v1.json` | ⚠️ 基础 | 15 个动作已注册（ui.* / app.* / notification.* / clipboard.* / device.* / telephony.* / sys.*） |

> ⚠️ **已知简化**：`ActionExecutorDispatcher.java` 中应用查询、Intent 解析、通知清除/回复仍为 MOCK 路径；节点→坐标解析在 v1 退化为「屏幕中心兜底」，未从 `UiSnapshot.bounds` 解析。overlay 版 `InputExecutor` 已使用真实 `InputManager.injectInputEvent`。

#### 感知层（Agent 的系统级感知通道）

| 模块 | 位置 | 状态 |
|---|---|---|
| **屏幕捕捉服务** | `AgentScreenService.java` + `FrameStreamManager.kt` | ✅ 中高 |
| **通知感知服务** | `AgentNotificationService.java` + `NotificationInterceptor.kt` | ✅ 中高 |
| **UI 树服务** | `AgentUiTreeService.java` + `UiTreeConsistencyValidator.java` | ✅ 中高 |
| **安全窗口检测器** | `SecureWindowDetector.kt` | ✅ 已实现 |

#### Agent 运行时（GentonBrain 特权系统应用，以下简称「大脑」）

| 模块 | 位置 | 状态 |
|---|---|---|
| **会话服务** | `session/AgentSessionServiceImpl.kt` | ✅ 高 |
| **规划器** | `planner/Planner.kt` + `GoalModels.kt` | ✅ 中高 |
| **工具循环** | `toolloop/ToolLoop.kt` | ✅ 中高 |
| **多 Agent 协调** | `multiagent/MultiAgentCoordinator.kt` | ✅ 中 |
| **DMI 桥接** | `dmi/DmiBridge.kt` + 8 个具体 Bridge | ✅ 中高 |
| **常驻小模型域** | `resident/ResidentModelManager.kt` | ⚠️ 框架完成 |
| **预测服务 (S1)** | `prediction/PredictionService.kt` | ⚠️ 框架完成 |
| **记忆系统集成** | `memory/` | ✅ 中 |
| **推理路由客户端** | `runtime/AgentRuntimeClient.kt` | ✅ 中 |
| **崩溃恢复 + Checkpoint** | `lifecycle/` + `checkpoint/` | ✅ 中 |

#### SystemUI 交互层（双模式一键切换）

| 模块 | 状态 | 说明 |
|---|---|---|
| **会话流 UI** | ✅ 中高 | 消息气泡、动作卡片、任务卡片、语音播放 |
| **确认面板** | ✅ 中高 | 全屏确认、FLAG_SECURE 防截屏 |
| **悬浮入口** | ✅ 中高 | 边缘手势、悬浮球、侧边栏 |
| **双模式系统** | ✅ 中高 | AI 助理模式 / 普通模式状态机、Launcher 桥接 |
| **活性指示器** | ✅ 中 | Agent 活动可视化提示 |
| **隐私仪表盘** | ✅ 中高 | 审计时间线、链完整性展示 |
| **规则编辑器** | ✅ 中 | 策略 DSL 可视化编辑 |
| **通话门 UI** | ✅ 中 | 来电拦截 + AI 接管 + 摘要 |
| **记忆管理 UI** | ✅ 中 | 实体管理 + 学习规则治理 |
| **姿态交互 UI** | ✅ 中 | 摇一摇就绪 + 翻面备忘 + 手势设置 |

#### 扩展系统服务

| 模块 | 状态 | 说明 |
|---|---|---|
| **记忆系统服务** | ✅ 高 | 三层记忆 + PII 掩码 + Provenance + 分级加密（AES-GCM）+ 遗忘级联（~1047 行）；⚠️ **语义检索当前为文本匹配**（向量索引结构已就绪，embedding 检索待接入） |
| **推理路由服务** | ⚠️ 框架完成 | 三级档位路由 + KV 缓存结构 + 内存管理 + 用量计量已实现（~1164 行）；⚠️ **推理后端为模拟实现**（`simulateInference`），NPU delegate 与真实 runtime 待接入 |
| **决策服务** | ⚠️ 中 | Choice/Bool/Score 三类决策 + 旗舰 SoC 能力位判定 + 降级链已实现；⚠️ **本地模型加载器与云端调用均未接通**（`tryLoadLocalModel()` 恒返回 false、云端为注入点占位），当前统一走规则/云端降级链 |
| **MotionHub 姿态服务** | ✅ 中高 | 三级档位 (T1/T2/T3) + 摇一摇/翻面/划动手势 + 敏感场景门控 |
| **通话门服务** | ✅ 中高 | 来电判定 + 白名单 + L1/L2/L3 接管 + 紧急号码保底 + 50ms 超时 fail-safe |
| **宏录制服务** | ✅ 中 | 录制 → 脚本生成 → 重放校验 |
| **意图学习服务** | ✅ 中 | 定期扫描 + 候选生成 + 建议生命周期 + 策略引擎联动 |
| **实体抽取服务** | ✅ 中 | 联系人/日历/会话/通知/用户输入多源抽取 |
| **上下文打包服务** | ✅ 中 | packContext API + 实体变更缓存失效 |

---

## 项目结构

```
Genton AI OS/
├── overlay/                          # Genton AI OS 覆盖层（AOSP 源码树补丁）
│   ├── frameworks/base/
│   │   ├── core/java/com/genton/  # AIDL 接口与共享数据模型（21 个包，243 个 .aidl）
│   │   ├── services/core/java/      # system_server 系统服务实现（61 个服务）
│   │   ├── packages/SystemUI/       # SystemUI 深度定制（双模式/会话UI/确认面板...）
│   │   └── data/etc/                # 权限声明 + sysconfig
│   └── system/sepolicy/private/     # SELinux 策略（5 个 .te + file/service/seapp_contexts）
├── frameworks/base/                 # Koltin 版框架服务（早期版本，向 Java 版收敛中）
├── packages/
│   ├── apps/GentonBrain/          # Agent 大脑特权系统应用（108 个 Kotlin 文件）
│   └── SystemUI/                    # SystemUI 独立模块（早期版本，向 overlay 版收敛中）
├── vendor/genton/                 # 平台配置 + 构建脚本 + 策略 DSL 基线
│   ├── config/                      # 基线策略、动作注册表、值域目录、机型校准
│   ├── build/                       # 产品 mk + AndroidProducts.mk
│   ├── services/                    # Python 版策略引擎参考实现
│   └── templates/                   # 第三方 Agent 开发模板（Sample Agent）
├── registry/                        # 动作注册表（与构建树内同步）
│   ├── action-registry.v1.json
│   └── action-registry.schema.json
├── patches/                         # 补丁集（14 个 patch 包 + 应用脚本）
│   ├── p0-confirmation-host/        # 确认宿主
│   ├── p1-assistant-ui-indicator/   # 活性指示器
│   ├── p2-floating-button-dual-mode/# 悬浮按钮双模式
│   ├── p3-cognitive-shell/          # 认知壳
│   ├── p4-brain-in-image/           # 大脑集成
│   ├── p5-missing-services/         # 缺失服务补全
│   ├── w8-boot-fixes/               # 启动修复
│   ├── gentonbrain-contract/      # Brain 契约
│   ├── inference-integration/       # 推理集成
│   └── mainline2/                   # Mainline 模块适配
├── tools/                           # 开发与测试工具
│   ├── policy_dsl_compiler.py       # 策略 DSL 编译器
│   ├── policy_pack_manager.py       # 策略包管理器
│   ├── test_*.py                    # 单元测试与集成测试脚本（24 个）
│   ├── wsl_*.sh / wsl_*.py          # WSL 构建与诊断工具（60+）
│   ├── ci/                          # CI 脚本
│   ├── e2e/                         # E2E 测试
│   ├── redteam/                     # 红队测试
│   └── token_signer/                # 令牌签名工具
├── verification/                    # 静态验证门禁
│   ├── check_*.py                   # 安全检查脚本（14 项）
│   ├── redteam/                     # 红队用例登记册（18 条攻击路径）
│   ├── run_all.py                   # 一键运行所有检查
│   └── README.md                    # 验证体系说明
├── manifests/                       # repo manifest（同步 AOSP + Genton AI OS 定制）
├── keys/                            # 开发签名密钥（仅开发用，生产密钥须离线管理）
├── ml/pii-mask-model/               # PII 掩码小模型资源
└── README.md                        # 本文档
```

---

## 部署与开发

### 前置依赖

- **操作系统**：Windows (WSL2 Ubuntu 22.04+) 或 Linux
- **磁盘空间**：≥ 200GB（AOSP 源码 + 构建产物）
- **内存**：≥ 16GB RAM（推荐 32GB+）
- **工具链**：repo、git、JDK 17、Python 3.10+

### 快速开始（轨道 A：Android 深度定制）

#### 1. 初始化源码树

```bash
# 安装 repo 工具
mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
export PATH=~/bin:$PATH

# 初始化仓库
mkdir genton-ai-os && cd genton-ai-os
repo init -u https://github.com/forrestsong/Genton-AI-OS -b main -m manifests/genton.xml
repo sync -c -j$(nproc --all)
```

#### 2. 应用 Genton AI OS 定制层

定制层是单一仓库（monorepo），由 manifest 检出到 `genton-layer/`：

```bash
# 查看部署计划（默认 dry-run，不写入）
./genton-layer/patches/apply-patches.sh

# 实际写入到当前源码树
./genton-layer/patches/apply-patches.sh --apply --target .
```

#### 3. 配置环境与构建

```bash
source build/envsetup.sh
lunch genton_arm64-eng
m -j$(nproc)
```

#### 4. 刷入设备（以 Pixel 系列为例）

```bash
adb reboot bootloader
fastboot flashall -w
```

#### 5. 模型权重与本地决策模型（可选，不随仓库与 AOSP 树分发）

**本仓库与 AOSP 源码树都不包含任何模型权重**（`.gitignore` 排除 `NeoHorse-Jev-4B/` 等权重目录）。系统在**完全没有模型**的前提下即可构建与启动——模型缺失时按降级链回退，不阻塞构建，也不影响安全门禁（策略引擎本身不含任何模型调用）。

**推理模型（推理路由服务）**

| 项 | 值 |
|---|---|
| 运行时目录 | `/system/genton/models/` |
| 常驻小模型（TIER_LOCAL_TINY） | `qwen2.5-0.5b-q4.gguf`（约 300MB） |
| 端侧大模型（TIER_LOCAL_LARGE） | `qwen2.5-7b-q4.gguf`（约 4GB） |

放置方式二选一：放入 `genton-layer/vendor/genton/models/` 并在产品 mk 中追加 `PRODUCT_COPY_FILES`；或经 OTA 包写入设备 `/system/genton/models/`。**当前推理后端为模拟实现**（`simulateInference`），即使放入权重也不会产生真实推理输出——接真实 runtime 属后续工作。

**本地决策模型（NeoHorse-Jev-4B，可选）**

用于意图分类、通知/反骚扰分类、推理档位判定等"带概率的类型化建议"。按机型能力位分双版本：

| 版本 | 判定条件 | 决策位置 |
|---|---|---|
| 旗舰版 | `decision_local_capable = true`（Snapdragon 8 Elite Gen 5 / 天玑 9500 / Exynos 2500） | 端侧本地推理 |
| 通用版 | 其余机型 | 经 `IAgentInferenceRouter` 的 TIER_CLOUD → CloudBroker `/v1/decision` |

- **能力位表**：`genton-layer/vendor/genton/config/device_capability_table_v1.json`，字段 `decision_local_capable` / `decision_runtime` / `decision_latency_p95_ms` / `npu_tops`；运行时读 `/vendor/genton/config/`
- **决策阈值**：`decision_thresholds_v1.json`；运行时依次读 `/vendor/genton/config/`、`/system/etc/genton/`
- **运行时开关**：系统属性 `persist.genton.decision.runtime`
- **部署建议**：**首次 bring-up 不要接入决策模型**。当前 `DecisionService.tryLoadLocalModel()` 仍是占位实现（恒定返回 `false`），本地模型路径尚未打通；此时决策统一走降级链（云端 → 确定性规则路径），系统可正常启动与验证。接入真机模型包时需补：签名校验 → NPU/内存加载 → 阈值标定（阈值按场景独立验证，不跨场景借用概率）
- **权重获取与合规**：NeoHorse-Jev-4B 采用 Apache-2.0，权重不随本仓库分发，请从其发布渠道获取；权重更新包需走与可信启动同款校验并分批灰度

### 开发指南

#### 添加一个新动作

1. 在 `registry/action-registry.v1.json` 中注册动作 Schema（输入/输出/风险级/所需能力/审计类型）
2. 在 `frameworks/base/services/.../action/executor/` 中实现对应执行器
3. 在 `AgentActionService.initializeExecutors()` 中注册执行器
4. 运行验证脚本确保合规：
   ```bash
   python tools/validate_action_registry.py
   python verification/check_1_1_no_prefix_bypass.py
   ```

#### 添加一条策略规则

1. 编辑 `vendor/genton/config/policy_baseline.dsl` 或新建策略包
2. 编译校验：
   ```bash
   python tools/policy_dsl_compiler.py --input your_policy.dsl
   ```
3. 运行策略测试套件：
   ```bash
   python tools/test_policy_engine_suite.py
   ```

#### 开发一个第三方 Agent

参考 `vendor/genton/templates/sample-agent/` 中的示例 Agent：
- 编写 `SandboxManifest.json` 声明能力与意图
- 实现 Agent 逻辑（支持 Kotlin/Java，未来支持更多语言）
- 经系统技能库注册后即可被用户授权使用

### 运行验证门禁

```bash
# 运行全部静态检查（任一失败 → 非零退出码）
python verification/run_all.py

# 单独运行某项检查
python verification/check_1_3_binder_authz.py
python verification/check_1_5_confirmation_chain.py

# 查看当前 Binder 鉴权状态
python verification/check_1_3_binder_authz.py --report
```

---

## 当前完成度速览

| 领域 | 完成度 | 说明 |
|---|---|---|
| 🔐 安全与信任底座 | 80% | 策略/审计/令牌/确认/鉴权核心机制完整，TEE 硬件背书待真机验证 |
| ⚡ 动作执行系统 | 70% | 框架 + 7 类执行器齐全，动作生态待丰富 |
| 👁️ 感知系统 | 65% | UI 树 / 通知 / 屏幕三大感知通道完成 |
| 🧠 Agent 运行时 | 60% | 会话/规划/工具循环/DMI 核心完成，多数模型后端为框架态 |
| 🎨 系统 UI | 70% | 双模式/会话流/确认面板/隐私仪表盘完成 |
| 🧩 扩展服务 | 55% | 记忆/推理路由/通话门/姿态服务等完成，沙箱服务待完善 |
| 🔧 构建与工具链 | 75% | 补丁系统/验证门禁/测试框架齐备 |

**总体评估**：架构完整，核心安全模块实现度较高，已具备「感知 → 意图理解 → 规划 → 安全执行 → 审计」的最小闭环。

---

## 参与共建

Genton AI OS 是一个开放的契约层项目。智能体原生操作系统的未来，不可能由任何一家公司封闭完成——它需要模型厂商、硬件厂商、软件服务商、安全研究社区的共同参与，才能真正建立起多方信任的合作基础。

### 不同主体的参与方式

| 参与方 | 可参与方向 |
|---|---|
| **模型厂商** | 接入推理路由服务，按契约提供推理能力；模型权重与数据边界受系统隔离保护，调用量可审计、可验证 |
| **硬件厂商** | 适配 NPU / TEE / 传感器能力，接入系统能力层；硬件能力以能力令牌形式被上层调用，调用可追溯、可结算 |
| **软件服务商** | 以技能 / DMI / 宏的形式接入资产库，在策略框架内提供服务；能力范围与数据访问严格受限，用户授权透明可见 |
| **安全研究者** | 贡献红队用例、发现并报告安全漏洞、增强策略基线与验证门禁 |
| **开发者社区** | 扩展动作生态、丰富策略规则库、优化执行性能、编写第三方 Agent 模板 |

### 贡献指南

> TODO：贡献者指南编写中。在此之前，欢迎通过 Issue / PR 参与讨论。

---

## 许可

本项目采用 **MIT 开源许可**，允许任意修改、分发及商业使用。详情参见 [LICENSE](LICENSE) 文件。

---

> **Genton AI OS：可信契约 + HUI 交互 + 多方协作。**
>
> Genton AI OS 目前处于早期开发阶段，核心架构与信任底座已具备可用形态。欢迎各方共建一个开放、可信的 AI 原生操作系统底座。
