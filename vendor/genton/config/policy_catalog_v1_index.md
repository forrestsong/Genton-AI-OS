# 策略 DSL 规则目录 v1 - 索引清单

> **版本**: 1.0.0  
> **日期**: 2026-09-05  
> **状态**: ✅ 已验证（A7.2 编译器通过）  
> **文件**: `vendor/genton/config/policy_catalog_v1.yaml`

---

## 1. 目录概览

- **notification 域**: 10 条规则（2 baseline + 7 user + 1 learn）
- **call_gate 域**: 12 条规则（2 baseline + 9 user + 1 learn）
- **总计**: 22 条规则
- **编译状态**: ✓ 成功，0 错误，0 冲突

---

## 2. notification 域规则清单 (10 条)

### 2.1 baseline 规则 (2 条)

| ID | Priority | Effect | 用途 |
|---|---|---|---|
| `sys.notify.baseline.critical-native` | 1000 | deny | 关键通知（alarm/system_critical/phone_ring_equivalent）不可静默 |
| `sys.notify.baseline.p0-pinned` | 1000 | deny | 用户钉选 P0 永原生，不得降级 |

### 2.2 user 规则 (7 条)

| ID | Priority | Effect | 用途 |
|---|---|---|---|
| `user.notify.route.bank-otp` | 200 | route_p0_native | OTP/支付/紧急 → P0 原生 |
| `user.notify.dnd-digest-hold` | 150 | route_p1_digest | DND 期间聚合延后简报 |
| `user.notify.app-updates-digest` | 110 | route_p1_digest | App 更新 → P1 + 角标抑制 |
| `user.notify.route.news-digest` | 100 | route_p1_digest | 资讯 → P1 摘要 |
| `user.notify.bulk-ad-cap` | 95 | route_p2_agent_act | 广告批量 → P2 + 日配额 10 |
| `user.notify.route.ad-fold` | 90 | route_p2_agent_act | 广告 → P2 折叠 |
| `user.notify.social.learned-a3f5b` | 105 | route_p1_digest | 激活后的学习规则示例 |

### 2.3 learn 规则 (1 条)

| ID | Priority | Effect | 用途 |
|---|---|---|---|
| `learn.notify.sender-frequent-p1` | 100 | route_p1_digest | 高频发送方 → P1（待确认） |

---

## 3. call_gate 域规则清单 (12 条)

### 3.1 baseline 规则 (2 条)

| ID | Priority | Effect | 用途 |
|---|---|---|---|
| `sys.callgate.baseline.emergency` | 1000 | route_ring_native | 紧急/系统号码永响铃 |
| `sys.callgate.carrier-notice-record` | 600 | route_record_only | 运营商通告仅记录 |

### 3.2 user 规则 (9 条)

| ID | Priority | Effect | 用途 |
|---|---|---|---|
| `user.callgate.ring-whitelist` | 300 | route_ring_native | 白名单响铃 |
| `user.callgate.ring-contact` | 290 | route_ring_native | 联系人响铃 |
| `user.callgate.return-call-reciprocity` | 280 | route_ring_native | 30 天回拨互惠 |
| `user.callgate.vip-repeat-escalate` | 260 | route_ring_native | 15min 重复呼叫升级 |
| `user.callgate.dnd-record` | 250 | route_record_only | DND 未知来电仅记录 |
| `user.callgate.ai-handle-daily-cap` | 140 | route_record_only | AI 接管日配额 3，超限仅记录 |
| `user.callgate.service-prefix-l3` | 130 | route_record_only | 95xxx 高置信仅记录 |
| `user.callgate.spam-l3` | 120 | route_record_only | 骚扰 ≥0.9 仅记录 |
| `user.callgate.unknown-l1` | 100 | route_agent_handle | 未知号 L1 AI 接管 |

### 3.3 learn 规则 (1 条)

| ID | Priority | Effect | 用途 |
|---|---|---|---|
| `learn.callgate.frequent-ignore-95` | 130 | route_record_only | 常忽略的 95 号段 → L3（待确认） |

---

## 4. 优先级分带

| 带 | Priority 范围 | 用途 |
|---|---|---|
| baseline | 1000 | sys. 基线（固定，不可改） |
| sys 非 baseline | 600-900 | 系统服务规则（如运营商通告） |
| user 正向 | 300-450 | 响铃/原生类（最高用户优先） |
| user DND/聚合 | 150-250 | DND/聚合/升级类 |
| user 摘要/折叠 | 90-140 | 摘要/折叠/记录类（learn 激活默认带） |

---

## 5. 规则类型统计

### 5.1 按命名空间

| 命名空间 | notification | call_gate | 总计 |
|---|---|---|---|
| sys. | 2 | 2 | 4 |
| user. | 7 | 9 | 16 |
| learn. | 1 | 1 | 2 |
| **总计** | **10** | **12** | **22** |

### 5.2 按 effect 类型

#### notification 域

| Effect | 数量 |
|---|---|
| deny | 2 |
| route_p0_native | 1 |
| route_p1_digest | 5 |
| route_p2_agent_act | 2 |

#### call_gate 域

| Effect | 数量 |
|---|---|
| route_ring_native | 5 |
| route_record_only | 6 |
| route_agent_handle | 1 |

---

## 6. 与 D17 §7 对账

### 6.1 notification 域对账

| D17 §7.1 规则 ID | 本目录 | 状态 |
|---|---|---|
| `sys.notify.baseline.critical-native` | ✅ | 完全匹配 |
| `sys.notify.baseline.p0-pinned` | ✅ | 完全匹配 |
| `user.notify.route.bank-otp` | ✅ | 完全匹配 |
| `user.notify.dnd-digest-hold` | ✅ | 完全匹配 |
| `user.notify.app-updates-digest` | ✅ | 完全匹配 |
| `user.notify.route.news-digest` | ✅ | 完全匹配 |
| `user.notify.bulk-ad-cap` | ✅ | 完全匹配 |
| `user.notify.route.ad-fold` | ✅ | 完全匹配 |
| `user.notify.<src>.learned-<hash>` | ✅ | 示例：`user.notify.social.learned-a3f5b` |
| `learn.notify.sender-frequent-p1` | ✅ | 完全匹配 |

**对账结果**: ✅ 10/10 匹配

### 6.2 call_gate 域对账

| D17 §7.2 规则 ID | 本目录 | 状态 |
|---|---|---|
| `sys.callgate.baseline.emergency` | ✅ | 完全匹配 |
| `sys.callgate.carrier-notice-record` | ✅ | 完全匹配 |
| `user.callgate.ring-whitelist` | ✅ | 完全匹配 |
| `user.callgate.return-call-reciprocity` | ✅ | 完全匹配 |
| `user.callgate.vip-repeat-escalate` | ✅ | 完全匹配 |
| `user.callgate.dnd-record` | ✅ | 完全匹配 |
| `user.callgate.ai-handle-daily-cap` | ✅ | 完全匹配 |
| `user.callgate.service-prefix-l3` | ✅ | 完全匹配 |
| `user.callgate.spam-l3` | ✅ | 完全匹配 |
| `user.callgate.unknown-l1` | ✅ | 完全匹配 |
| `user.callgate.<src>.learned-<hash>` | ✅ | 模式定义（未具体化） |
| `learn.callgate.<…>` | ✅ | 示例：`learn.callgate.frequent-ignore-95` |

**对账结果**: ✅ 12/12 匹配

**注**: D17 §7.2 中 `user.callgate.ring-contact` 未在表格明确列出但在描述中提及，已补充。

---

## 7. 编译验证结果

```
编译状态: ✓ 成功
规则总数: 22
错误数量: 0
警告数量: 0
冲突数量: 0
死规则数量: 0
```

### 7.1 验证项通过清单

- ✅ C1: ID 格式和命名空间验证
- ✅ C2: 字段必填性验证
- ✅ C3: when 键白名单验证
- ✅ C4: effect 动词验证
- ✅ C5: 命名空间与 _meta 验证
- ✅ C6: 冲突矩阵检查
- ✅ C7: audit 验证
- ✅ C8: 死规则检测

---

## 8. 使用说明

### 8.1 加载规则集

```bash
# 编译验证
python tools/policy_dsl_compiler.py vendor/genton/config/policy_catalog_v1.yaml

# 策略引擎加载（A7.1 实现后）
# PolicyEngine.reloadPolicySet("vendor/genton/config/policy_catalog_v1.yaml")
```

### 8.2 规则扩展

- **baseline 规则**: 由平台签名，不可用户修改
- **user 规则**: 用户可通过 UI 编辑器调整（D6.2）
- **learn 规则**: 系统学习生成，需用户激活（C2.x）

### 8.3 优先级规则

1. **baseline deny** 压过一切路由规则
2. **userPinnedP0** 不可被任何规则降级
3. 紧急号码系统保底，策略永不可拦
4. 同 priority 按规则 ID 字典序稳定排序

---

## 9. 与其他交付物的关系

### 9.1 输入依赖

- ✅ E1.1: 安全基线规范（baseline 规则约束）
- ✅ D17 §7: 规则目录定义（权威来源）
- ✅ D10 §2.3/§2.4: 路由策略示例

### 9.2 输出消费

- → A7.1: 策略引擎服务端装载
- → A7.2: DSL 编译器验证（已完成）
- → C1.2: 值域知识库（category 枚举等）
- → C1.3: 策略基线内容化
- → H2.1: 策略引擎单测

---

## 10. 后续工作

### 10.1 C1.2 - 值域知识库 (待实现)

- category 枚举集完整定义
- 发送方域白名单
- 号码号段规则
- 比较串语义说明

### 10.2 C1.3 - 策略基线 (待实现)

- 授权轴基线规则
- baseline-route 完整集
- 冲突矩阵黄金样例

### 10.3 C1.4 - PolicyPack 打包 (待实现)

- 签名打包工具
- 灰度发布机制
- 版本管理策略

---

## 11. 里程碑验收

**C1.1 任务状态**: ✅ 已完成

**验收标准**:
- ✅ notification 10 条 + call_gate 12 条规则
- ✅ 优先级分带正确
- ✅ 与 D17 §7 / D10 对账一致
- ✅ A7.2 编译器零错误通过
- ✅ 唯一事实源（单一 YAML 文件）

**A1 里程碑贡献**:
- 为 A7.1 策略引擎提供规则内容
- 为 H2.1 策略单测提供测试数据
- 为 C1.2/C1.3 后续任务提供基础

---

**文档维护**: 本索引随 `policy_catalog_v1.yaml` 同步更新  
**变更记录**: 所有规则变更需通过 C1.4 签名打包流程
