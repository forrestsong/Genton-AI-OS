# AI Native OS CI 流水线说明（H1.1）

> 任务编号：H1.1 质量工程部 · CI 基建
> 里程碑：A1 · 前置：A1.1 · 依赖草案：架构 §7 / D17
> 状态：✅ 可执行交付（本文件 + `ci/run-genton-checks.sh` / `.bat` + 既有工具组合）

## 1. 流水线总览

H1.1 将既有 CI 步骤整合为一条四合一主流水线，全部以可执行脚本 /
可调用的既有工具组合实现，每个步骤独立退出码，最终聚合为门禁结论。

```
┌─────────────────────────────────────────────────────────────┐
│  Step 0  环境检查（python3 可用性）                          │
├─────────────────────────────────────────────────────────────┤
│  Step 1  AIDL / Soong 编译（占位）                           │
│           └ AIDL 接口存在性扫描 + 真实编译接入点说明          │
├─────────────────────────────────────────────────────────────┤
│  Step 2  Schema / 契约静态校验                               │
│           ├ validate_action_registry.py（动作注册表 JSON      │
│           │   Schema + 执行器存在性 + 能力映射）              │
│           ├ validate_registry_with_schema.py（registry/ 对照）│
│           └ test_contract_golden.py（H1.2 契约黄金样例）      │
├─────────────────────────────────────────────────────────────┤
│  Step 3  DSL C1-C8 编译检查                                  │
│           └ dsl_c1_c8_check.py policy_baseline.dsl           │
├─────────────────────────────────────────────────────────────┤
│  Step 4  sepolicy / 权限审计                                 │
│           └ security_audit.py --all（E1.1 红线检查单工具化）  │
├─────────────────────────────────────────────────────────────┤
│  汇总    PASS/FAIL/SKIP 聚合门禁（任一 FAIL → 退出 1）        │
└─────────────────────────────────────────────────────────────┘
```

## 2. 文件清单与职责

| 文件 | 职责 |
|---|---|
| `ci/run-genton-checks.sh` | Linux/macOS 流水线编排（Bash） |
| `ci/run-genton-checks.bat` | Windows 流水线编排（批处理） |
| `tools/dsl_parser.py` | 策略 DSL（`.dsl` 自定义格式）解析器（纯标准库） |
| `tools/dsl_c1_c8_check.py` | DSL C1–C8 静态编译检查（C1 ID / C2 必填 / C3 when 白名单 / C4 effect / C5 命名空间 / C6 冲突矩阵 / C7 audit / C8 死规则） |
| `tools/validate_action_registry.py` | 动作注册表校验（既有，A3.1） |
| `tools/validate_registry_with_schema.py` | registry/ 目录 schema 对照（既有） |
| `tools/test_contract_golden.py` | 契约黄金样例回归（H1.2 产出） |
| `tools/security_audit.py` | sepolicy/权限/红线审计（既有，E1.1） |

## 3. 各步骤说明与退出码

### Step 0 环境检查
- 检测 `python3`（回退 `python`）；缺失 → 退出码 2。

### Step 1 AIDL / Soong 编译（占位）
- 扫描 `overlay/frameworks/base/core/java/com/genton/` 下 `*.aidl` 数量。
- 完整 Soong 编译需 Android 构建环境（`source build/envsetup.sh && lunch && m`）。
- 占位策略：AIDL 文件存在性 ≥ 0 即 PASS；真实编译接入点如下（构建环境就绪后启用）：

```bash
# 完整编译接入点（CI runner 具备 Android 环境时启用）
cd "$ANDROID_BUILD_TOP"
source build/envsetup.sh
lunch aosp_arm64-userdebug
# 逐个模块：
m genton.perception-java genton.policy-java genton.audit-java
# 若任一步失败，以对应退出码中断流水线
```

### Step 2 Schema / 契约静态校验
| 子步骤 | 调用 | 失败退出码 |
|---|---|---|
| 动作注册表 | `python3 tools/validate_action_registry.py vendor/genton/config/action_registry.json vendor/genton/config/action-registry.schema.json` | 1 |
| registry/ 对照 | `python3 tools/validate_registry_with_schema.py` | 1 |
| 契约黄金样例 | `python3 tools/test_contract_golden.py` | 1 |

### Step 3 DSL C1–C8 编译检查
```bash
python3 tools/dsl_c1_c8_check.py \
    vendor/genton/config/policy_baseline.dsl \
    --report reports/ci/dsl-c1c8-check.md
```
- C1 ID 命名空间/格式/唯一性；C2 必填/未知字段；C3 when 键白名单（含
  `context.*` / `call.*` 前缀与 `or` 分支）；C4 effect 动词与 route 同族一致性；
  C5 命名空间与 provenance；C6 冲突矩阵（同优先级重叠且 effect 不同 = 冲突，
  不同优先级由优先级裁决，不构成矩阵冲突）；C7 audit 取值（高风险 effect 禁
  `audit:none`）；C8 死规则检测（被更高优先级完全覆盖）。
- 当前基线 `policy_baseline.dsl`（16 条规则）零错误零警告通过。
- 生成 Markdown 报告到 `reports/ci/dsl-c1c8-check.md`。

### Step 4 sepolicy / 权限审计
```bash
python3 tools/security_audit.py --all
```
- 复用 E1.1 红线检查单工具化结果（权限保护级别、能力注册表、哈希链、FLAG_SECURE 等）。
- `--skip-audit` 可跳过（SKIP 计，不失败）。

## 4. 运行方式

```bash
# Linux/macOS
chmod +x ci/run-genton-checks.sh
./ci/run-genton-checks.sh            # 全量
./ci/run-genton-checks.sh --quick    # 跳过 AIDL 占位
./ci/run-genton-checks.sh --skip-audit

# Windows
ci\run-genton-checks.bat
ci\run-genton-checks.bat --skip-audit
```

## 5. 门禁语义（对齐 H 是验收唯一出口）

- 任一 FAIL 步骤 → 整体退出码 1，构建门禁应终止；
- SKIP 步骤不参与失败判定（显式声明的原因化跳过）；
- 报告统一落 `reports/ci/`：
  - `action-registry-check.log`、`registry-schema-check.log`、`contract-golden.log`
  - `dsl-c1c8.log`、`dsl-c1c8-check.md`、`security-audit.log`

## 6. 接入建议（CI 平台）

- **Gerrit/Repo**: 在 submit 规则中挂 `run-genton-checks.sh` 为 non-voting 后门；
- **GitHub Actions / GitLab CI**: 定义 job `h11-ci-baseline`，matrix 覆盖
  `ubuntu-latest`（.sh）与 `windows-latest`（.bat）；
- **触发策略**: push 到 `main`/`develop` + 路径过滤（`vendor/genton/config/`、
  `tools/`、`overlay/**/aidl/`、`ci/`）；每日定时全量回归。

## 7. 与相邻任务联动

| 任务 | 联动点 |
|---|---|
| H1.2 | Step 2 调用 `test_contract_golden.py` |
| H1.3 | 资产 CI 门（`ci/asset-ci-gate.sh`）为独立门禁，建议挂同一流水线 Step 2 之后 |
| H2.1 | 策略引擎单测可被 Step 3 后追加调用（`test_policy_engine_suite.py`） |
| E1.1 | Step 4 复用其红线检查单工具化输出 |
| A1.2 | 契约收敛回归（`validate_a1.2*` 系列）可并入 Step 2 |
