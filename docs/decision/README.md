# 本地决策模型（NeoHorse-Jev-4B）文档目录

> WBS 0.4 增补：B5.3/B5.4 本地决策模型集成（见技术架构 §4.14）

## 文档

| 文件 | 内容 |
|---|---|
| [B5.3-B5.4-决策模型集成设计.md](./B5.3-B5.4-决策模型集成设计.md) | IAgentDecisionService 契约草案、决策适配器映射表（10 个适配器）、阈值表纪律、部署形态、评测验收口径 |
| NeoHorse-Jev-4B 上游模型说明（评测/部署/决策类型） | **不随本仓库分发**（权重目录 `NeoHorse-Jev-4B/` 由 `.gitignore` 排除），见项目 README 的「模型权重与本地决策模型」部署说明 |

## 关联

- 环境搭建与模型投放：见项目 [README](../../README.md) 的部署章节第 5 步
- 机型适配：`vendor/genton/config/device_capability_table_v1.json`（含 `decision_local_capable` 能力位与 `decision_runtime` / `decision_latency_p95_ms` 字段）
