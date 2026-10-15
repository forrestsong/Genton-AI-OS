# com.genton.decision — 本地决策模型服务（B5.3）

NeoHorse-Jev-4B prefill-only 结构化决策服务。**旗舰版/通用版双后端**：
- **旗舰版**（第五代骁龙 8 至尊版 / 天玑 9500 / Exynos 2500）：端侧本地推理（NPU/CPU）
- **通用版**（其余机型）：经 `IAgentInferenceRouter` TIER_CLOUD → CloudBroker `/v1/decision` 云端决策 API

## 接口

| 接口 | 说明 |
|---|---|
| `IAgentDecisionService` | choose（Choice 候选选择）/ judge（Noul 是非）/ score（Score 评分）+ getBackendStatus + getThresholdVersion |
| `DecisionRequest` | scenario / state / candidates / question / constraints / budget / 溯源字段 |
| `DecisionResult` | selected / confidence / probabilities / backend / statusCode / fallbackReason |

## 决策类型

- **Choice**：候选选择（意图分类/工具选择/通知 category/推理档位/S1 预测）
- **Noul**：是非判定（策略语义条件/工作流门控/learn 采纳）
- **Score**：分级评分（反骚扰 spamScore/优先级/结果自评）

## 红线

模型只出"带概率的建议"；授权/deny 默认/路由常量仍由策略引擎（A7）确定性裁决；决策结果全量落审计链。

详见 [docs/decision/B5.3-B5.4-决策模型集成设计.md](../../../../../../../docs/decision/B5.3-B5.4-决策模型集成设计.md)。
