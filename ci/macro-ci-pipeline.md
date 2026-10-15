# 宏资产 CI 校验流水线（F1.2）

> **任务编号**：F1.2 宏资产 CI 校验流水线（黄金样例重放、失败自动停用回灌 A8.1、版本基线管理）
> **对应草案**：D11（技能与宏库服务接口）+ D9 S2（宏录制/重放）
> **前置**：F1.1 宏录制工具链 ✅、H1.1 CI 基建（对齐）
> **配合**：A8.1 资产目录服务（回灌目标）、H1.3 资产 CI 门（消费本流水线）、F1.3 宏库规模化（增量回归输入）、H4.1 真机矩阵（运行器）
> **相关文档**：`docs/macro-testing-specification.md`（黄金样例格式/结果类型）、`ci/macro-ci-config.yaml`（阈值配置）、`reports/A8.1_完成报告.md`（setAssetStatus/staged-enable）

---

## 1. 定位与目标

宏资产（像素/坐标宏，见 F1.1）以坐标与感知描述驱动 UI 操作，**对机型、App 版本、系统版本高度敏感**。
本流水线在每次宏资产变更（提交/定时/手动）时自动完成：

1. **黄金样例重放**：把人工验证过的黄金样例（`tests/macro/golden/*.json`）在目标设备上重放，验证行为未回归；
2. **失败自动停用**：连续失败 / 高失败率达到阈值时，自动将宏标记停用并**回灌 A8.1 资产目录服务**（调用占位见 §5），防止"坏宏反复拖慢 Agent"（对齐 A8.1 执行回灌统计）；
3. **版本基线管理**：记录每个宏的已知可用版本，检出"版本漂移"（App/系统更新导致宏失效）并提示人工适配；
4. **多格式报告**：`report.json` + `junit.xml`，供 H1.3 资产 CI 门与通知消费。

---

## 2. 流水线架构

```
                      ┌──────────────────────────────────────────────┐
  触发                 │              run-macro-ci.sh / .bat          │
  on_commit ─────────► │  Stage 1 静态校验（黄金样例 schema 存在性）     │
  on_schedule ───────► │  Stage 2 设备检查（offline 模式跳过）           │
  on_demand ─────────► │  Stage 3 黄金样例重放（F1.1 MacroPlayback）    │
                      │  Stage 4 失败率检查 → 自动停用 → 回灌 A8.1       │
                      │  Stage 5 版本基线管理                          │
                      └──────────┬───────────────────────────────────┘
                                 │
        ┌────────────────────────┼───────────────────────────┐
        ▼                        ▼                           ▼
  report.json / junit.xml   auto-disable-queue.json   macro-baseline.json
  （reports/ci/macro/）     （ci/state/ 回灌清单）     （ci/state/ 版本基线）
        │                        │                           │
        ▼                        ▼                           ▼
  H1.3 资产 CI 门        A8.1 setAssetStatus(disabled)  F1.3 增量回归输入
  （构建门禁）            （占位，H1.3 替换为真实 IPC）   （漂移宏优先补充样本）
```

核心逻辑位于 `tools/macro_ci_core.py`（python3 标准库实现），`.sh`/`.bat` 为薄编排层，
与仓库既有 `ci/*-check.sh|.bat` + `tools/*.py` 的模式一致。

---

## 3. 文件与目录约定

| 路径 | 说明 |
|---|---|
| `ci/run-macro-ci.sh` / `ci/run-macro-ci.bat` | 流水线入口（Linux/macOS / Windows） |
| `tools/macro_ci_core.py` | 核心逻辑：validate / replay / gatedisable / baseline |
| `ci/macro-ci-config.yaml` | 阈值与触发配置（对齐源） |
| `tests/macro/golden/*.json` | 黄金样例（F1.3 样本扩充的主战场） |
| `reports/ci/macro/report.json` / `junit.xml` | 每次运行的报告 |
| `ci/state/macro-ci-state.json` | 连续失败计数器（跨运行持久化，1h 窗口） |
| `ci/state/auto-disable-queue.json` | A8.1 回灌清单（待 H1.3 消费） |
| `ci/state/macro-baseline.json` | 版本基线 |

---

## 4. 运行方式与触发

### 4.1 命令行

```bash
# Linux/macOS
./ci/run-macro-ci.sh --offline-validate     # 静态校验 + 基线 dry-run（无需设备）
./ci/run-macro-ci.sh --device 192.168.1.10  # 指定设备全量重放

# Windows
ci\run-macro-ci.bat --offline-validate
ci\run-macro-ci.bat --update-baseline       # 采纳当前版本为基线（修复漂移）
```

| 参数 | 说明 |
|---|---|
| `--offline-validate` | 仅静态校验黄金样例 schema + 基线 dry-run，不要求设备（适合提交前自检） |
| `--update-baseline` | 采纳当前版本为基线（修复版本漂移导致的失败） |
| `--device <serial>` | 指定 adb 设备序列号（默认 adb 默认设备） |
| `--golden-dir <dir>` | 黄金样例目录（默认 `tests/macro/golden`） |
| `--out-dir <dir>` | 报告输出目录（默认 `reports/ci/macro`） |
| `--help` | 帮助 |

### 4.2 触发方式（对齐 `ci/macro-ci-config.yaml` `trigger`）

| 触发 | cron / 条件 | 说明 |
|---|---|---|
| `on_commit` | 分支 `main`/`develop`，路径 `macros/**`、`tests/macro/**` | 提交即跑（冒烟+黄金） |
| `on_schedule` | `0 2 * * *`（每日回归）、`0 */4 * * *`（增量） | 黄金样例全量回归 |
| `on_demand` | 手动 | 排查/验收用 |

---

## 5. 失败自动停用与 A8.1 回灌

### 5.1 判定阈值（对齐 `macro-ci-config.yaml` 与 `macro-testing-specification.md` §5.2）

| 策略 | 条件 | 说明 |
|---|---|---|
| 连续失败 | 连续失败 ≥ 3 次（1h 窗口内） | 状态跨运行持久化于 `macro-ci-state.json` |
| 高失败率 | 样本 ≥ 5 且失败率 ≥ 70% | 单次运行内统计 |

失败计数规则：`fail`/`timeout` 计入失败；`pass` 重置连续计数；`error`（重放基础设施不可用）
**不计入**自动停用，单独以 ENV_ERROR 呈现；`skip`（offline/机型裁剪）不计。

### 5.2 A8.1 回灌（调用占位）

触发停用后，流水线在 `ci/state/auto-disable-queue.json` 写入回灌清单，每条含：

```json
{
  "assetId": "macro:weather.open_today",
  "reason": "连续失败 3 次 ≥ 阈值 3（窗口 1h 内）",
  "consecutiveFailures": 3,
  "failureRate": 1.0,
  "status": "queued",
  "a81DisableCall": "adb shell service call agent_asset_library <TXN_SET_ASSET_STATUS> s16 macro:weather.open_today i32 1   # 占位：需 H1.3 替换为真实 binder 调用"
}
```

> **占位说明**：目标调用为 A8.1 `IAgentAssetLibraryService.setAssetStatus(assetId, ASSET_STATUS_DISABLED=1)`
> （见 `reports/A8.1_完成报告.md`）。AIDL 事务码（`<TXN_SET_ASSET_STATUS>`）与 binder 接线
> 由 **H1.3 资产 CI 门**接入时替换为真实 IPC（或 `setAssetStatus` 的系统广播），
> 本流水线以"队列 + 占位命令"形式保证失败自动停用的语义可联调、可消费、可审计。
> 消费方（H1.3）读取该队列后应：① 调用 A8.1 停用；② 通知宏 owner（对齐 `auto_disable.on_disable.notify`）；③ 记录审计。

### 5.3 与 A8.1 执行回灌统计的闭环

A8.1 侧另有 `reportExecution` 执行统计（连续失败 3 次 + 1h 窗口自动停用）。
本流水线的 `gatedisable` 与之**双保险**：A8.1 在运行期按真实执行结果停用；
本流水线在**回归期**（黄金重放）提前发现并停用，避免坏宏进入生产解析链。

---

## 6. 版本基线管理

- 基线文件 `ci/state/macro-baseline.json` 记录 `{macroId: {version, firstSeenAt, lastSeenAt, status}}`；
- 每次运行提取黄金样例的 `macroVersion`（缺省 `unknown`）与基线比对：
  - **新增宏** → 记入基线（按 A8.1 staged-enable 流程观察，见 F1.3）；
  - **版本漂移** → 报告 `{macroId}: {old} → {new}`，默认退出码 1（需 review）；
    确认适配后用 `--update-baseline` 采纳新版本；
  - **无变化** → 基线静默刷新 `lastSeenAt`。
- 版本漂移通常意味着：App 更新、系统更新、坐标失效 —— 是 F1.3 **增量回归** 的主要触发源之一。

---

## 7. 退出码约定（脚本可编程消费）

| 退出码 | 含义 | 流水线行为 |
|---|---|---|
| **0** | 通过：无失败、无停用、无漂移 | 构建放行 |
| **1** | 测试失败或基线漂移 | 构建失败，需人工 review（未达停用阈值） |
| **2** | 有宏被自动停用并写入 A8.1 回灌清单 | 构建失败，需人工 review 停用项 |
| **3** | 环境错误：无设备 / 无 adb / 重放基础设施不可用 | 构建失败（设备类 CI） |
| **4** | 配置或 schema 错误：黄金样例缺失/不合法、依赖缺失 | 构建失败（配置类） |
| **130** | 用户中断 | — |

> 多个阶段的退出码取**最大值**（数值优先级恰好为 4>3>2>1>0），保证最严重问题优先暴露。

---

## 8. 报告格式

### 8.1 report.json（对齐 `macro-testing-specification.md` §8.1）

```json
{
  "generatedAt": "2026-10-05T08:00:00Z",
  "pipeline": "macro-ci",
  "mode": "device",
  "config": {"consecutive_failures": 3, "failure_rate": 0.7, "min_samples": 5, "required_pass_rate": 1.0},
  "summary": {"totalCases": 2, "passed": 1, "failed": 1, "error": 0, "skipped": 0,
              "totalMacros": 2, "passedMacros": 1, "failedMacros": 1, "disabledMacros": 0},
  "macros": [{"macroId": "macro:weather.open_today", "macroName": "查询北京天气",
              "macroVersion": "1.2.0", "totalTests": 1, "passed": 0, "failed": 1,
              "timeout": 0, "error": 0, "skipped": 0, "status": "fail"}],
  "cases": [{"testCaseId": "tc_macro_weather_query_001", "macroId": "macro:weather.open_today",
             "result": "fail", "failureReason": "...", "screenshotRef": "/sdcard/..."}]
}
```

### 8.2 junit.xml

标准 JUnit XML（`testsuites/testsuite/testcase`），可直接接入既有 CI 面板（Jenkins/GitLab 等）。

---

## 9. 配置项对齐表（`ci/macro-ci-config.yaml` ↔ 脚本行为）

| 配置键 | 默认/示例值 | 脚本消费点 |
|---|---|---|
| `trigger.on_commit` | branches/paths | CI 平台侧决定是否触发（脚本不内嵌） |
| `trigger.on_schedule` | cron | CI 平台侧调度 |
| `stages[].timeout_ms` | 300000 | 设备重放阶段总超时（由 CI 平台限时） |
| `test_config.golden_tests.required_pass_rate` | 1.0 | 黄金样例必须 100% 通过（validate/replay 判定） |
| `auto_disable.strategies[].consecutive_failures` | 3 | `gatedisable` 连续失败阈值 |
| `auto_disable.strategies[].high_failure_rate.threshold` | 0.7 | `gatedisable` 失败率阈值 |
| `auto_disable.strategies[].high_failure_rate.min_samples` | 5 | `gatedisable` 最小样本数 |
| `reports.output_dir` / `retention_days` | reports/ci/macro / 30 | 报告输出目录（`--out-dir`） |

> `macro_ci_core.py` 以**正则提取**上述键（缺省回退默认值），不依赖 PyYAML，保持零第三方依赖。

---

## 10. 与相邻任务的关系

| 任务 | 关系 |
|---|---|
| **F1.1 宏录制工具链** | 提供宏脚本与 `MacroPlaybackValidator`（重放基础设施，`dispatch_case` 依赖其结果上报） |
| **F1.3 宏库规模化** | 本流水线是 F1.3 的**回归引擎**：per-机型样本扩充后由本流水线验证；staged-enable 观察数据来自 `report.json` |
| **A8.1 资产目录服务** | 回灌目标（`setAssetStatus`）、`reportExecution` 双保险 |
| **H1.3 资产 CI 门** | 消费本流水线产物（report + queue），负责 A8.1 真实 IPC 替换与通知 |
| **H4.1 真机矩阵** | 共享设备矩阵；`deviceTags` 字段（F1.3）供 per-机型筛选 |
| **F4.1/F4.2 校准库** | 提供机型档案与回归样本；本流水线黄金样例可引用其 `profileRef` |

---

## 11. 运行示例与排障

### 11.1 提交前自检（无设备）

```bash
$ ./ci/run-macro-ci.sh --offline-validate
[Stage 1/5] 黄金样例静态校验... 2/2 通过
[Stage 2/5] 设备检查... offline 模式跳过
[Stage 3/5] 黄金样例重放... 全部 skip（offline）
[Stage 4/5] 自动停用判定... 无宏触发
[Stage 5/5] 版本基线管理... 新增宏 2 个
Macro CI finished: exit code 0
```

### 11.2 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 退出码 4，提示黄金样例目录为空 | `tests/macro/golden/` 尚无样例 | 按 F1.3 扩充样本 |
| 退出码 3，提示未发现设备 | 未连接真机/模拟器或 adb 缺失 | 连接设备或加 `--device` |
| 退出码 1，全部 error | 设备上 F1.1 重放服务未部署（结果文件缺失） | 先联调 F1.1 MacroPlaybackValidator 上报链路 |
| 退出码 1，基线漂移 | App/系统版本变更 | 确认适配后 `--update-baseline` |
| 退出码 2 | 宏连续失败被自动停用 | 人工 review `auto-disable-queue.json` 后修复宏 |

---

**维护**：F 资产生态部（F1.2 宏 CI 组）
**版本**：1.0　**更新日期**：2026-10-05
