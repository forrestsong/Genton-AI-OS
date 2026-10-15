# 机型校准采集指南

> 任务编号：F4.1（机型校准矩阵与参数采集）/ F4.2（校准回归样本库）
> 版本：v1.1 | 更新日期：2026-09-10
> 相关规范：[machine-profile.schema.json](../vendor/genton/config/calibration/machine-profile.schema.json)、[suite.schema.json](../vendor/genton/config/calibration/regression/suite.schema.json)

本指南说明如何为 AI Native OS 采集**机型校准档案**（F4.1）并维护**校准回归样本集**（F4.2）：从真机取参 → 写入档案 → 校验 → 导出校准矩阵 → 冻结 per-机型黄金集。
校准档案被以下模块消费：

| 消费方 | 用到的字段 |
|--------|-----------|
| 宏重放（F1.1 `MacroScriptGenerator.calibrationParams`） | `macro.coordinateScaleX/Y`、`macro.calibrationParamsKeys` |
| 捕获降采样 / 帧率纪律（D9 §6h、A2.5、D8.2） | `render.captureDownsample`、`render.injectionFrameRateCap` |
| S7 姿态交互档位（A12.2a / A12.2b） | `s7.motionCapabilitiesHex`、`s7.tiers`、`s7.gestures` |
| DMI 可用性登记（F2.2 / F3.2） | `dmiAvailability.registryRef`、`dmiAvailability.overrides` |

---

## 1. 目录结构

```
vendor/genton/config/calibration/
├── machine-profile.schema.json      # 档案规范（权威 schema）
├── dmi-availability.json            # DMI 可用性登记表（与机型无关的基线）
├── profiles/
│   ├── sm8550_p0.json               # 旗舰 P0 样例
│   └── mt6897_p1.json               # 中端 P1 样例（深睡档裁剪）
└── regression/                      # F4.2 校准回归样本库
    ├── suite.schema.json            # 样本集规范（权威 schema）
    ├── sm8550_p0.suite.json         # 旗舰 P0 黄金集（22 用例，无跳过）
    └── mt6897_p1.suite.json         # 中端 P1 黄金集（23 用例，3 项裁剪跳过）
tools/calibration_collect.py         # 采集 / 校验 / 矩阵导出工具（仅标准库）
ci/calibration-check.sh|.bat         # CI 门禁入口
```

**新增机型**：复制 `profiles/` 下最接近的样例，改名 `profiles/<soc>_<tier>.json`，逐项采集后跑 `validate`；
再复制 `regression/` 下最接近的样本集，改名 `<soc>_<tier>.suite.json` 并改 `profileRef` 指向新档案，跑 `regression-validate`（见 §5）。

> `profileId` 必须匹配 `^[a-z0-9_]{3,32}$`（建议 `<soc>_<tier>`，如 `sm8550_p0`）。

---

## 2. 采集步骤（真机）

### 2.1 取参

```bash
# 1) 系统属性
adb shell getprop                    > props.txt

# 2) 显示信息
adb shell dumpsys display            > display.txt

# 3) 设备序列号（仅保留后 4 位用于追溯）
adb shell getprop ro.serialno
```

### 2.2 关注字段

`props.txt` 中需要关注的属性：

| 属性 | 映射到档案字段 |
|------|---------------|
| `ro.product.model` | `identity.deviceModel` |
| `ro.product.manufacturer` | `identity.deviceManufacturer` |
| `ro.build.version.release` | `identity.androidVersion` |
| `ro.build.version.sdk` | `identity.sdkVersion` |
| `ro.build.fingerprint` | `identity.buildFingerprint` |
| `ro.genton.motion.capabilities` | `s7.motionCapabilitiesHex`（并派生 `s7.tiers` / `s7.gestures`） |

> `identity.soc` / `identity.socModel`（如 `Qualcomm Snapdragon 8 Gen 2+` / `SM8550`）为**人工维护**的机型分级取值（对齐 A12.2b 能力位表），
> 不由设备属性覆盖——`ro.board.platform` 是平台代号（如 `kalama`），与 SoC 型号不等价。

`display.txt` 中需要关注：`mBaseDisplayInfo` 的 `real` / `deviceWidth` / `deviceHeight` / `density` / `refreshRate` / `supportedModes`。

> `ro.build.fingerprint` 用于宏版本绑定；变更后既有宏需按 `macro.binding` 维度判定失效（D9 §3）。
> `ro.genton.motion.capabilities` 由 `MotionHubService` 在启动时自 `ro.genton.motion.capabilities` 读入，位定义见 [A12.2a](A12.2a_OEM硬件能力位确认方案.md)。

### 2.3 时序实测（可选）

点击/滑动注入耗时由宏录制样本聚合（工具取中位数）。样本文件结构：

```json
{
  "recordings": [
    {
      "steps": [
        { "type": 0, "durationMs": 42 },
        { "type": 1, "durationMs": 260 }
      ]
    }
  ]
}
```

`type`：`0` = TAP，`1` = SWIPE（对齐 `MacroStep` 类型枚举）。

---

## 3. 设备 dump JSON 结构

`collect` 子命令的输入。**手工按此结构整理 `props.txt` / `display.txt`。**

```json
{
  "collectedAt": "2026-09-10T14:30:00+08:00",
  "collectedBy": "calibration-bot",
  "serialMasked": "****1234",
  "props": {
    "ro.product.model": "SM-S9180",
    "ro.product.manufacturer": "samsung",
    "ro.board.platform": "kalama",
    "ro.build.version.release": "16",
    "ro.build.version.sdk": "36",
    "ro.build.fingerprint": "samsung/dm3qxxx/dm3q:16/...",
    "ro.genton.motion.capabilities": "0x7700037F"
  },
  "display": {
    "widthPx": 1440,
    "heightPx": 3088,
    "densityDpi": 480,
    "refreshRateHz": 120.0,
    "nativeRefreshRates": [60.0, 96.0, 120.0]
  },
  "macroSamples": "device-dump/macro-samples.json"
}
```

字段说明：

| 字段 | 必需 | 说明 |
|------|------|------|
| `props` | 建议 | 属性名 → 值；仅 §2.2 表中的键会被写入档案，其余忽略 |
| `display` | 建议 | 显示参数；`nativeRefreshRates` 缺省时不覆盖档案 |
| `macroSamples` | 否 | **文件路径字符串**（非内联对象），用于时序聚合 |
| `serialMasked` | 否 | 写入 `measurement.deviceSerialMasked`（仅保留后 4 位） |
| `collectedAt` / `collectedBy` | 否 | 写入 `measurement.*`，用于来源追溯 |

> 未在 dump 中出现的字段不会被清空——档案中该字段保持原值（通常为 `null` 并留在 `pendingFields`）。
> 坐标缩放因子由工具自动推导：以 `1080x2400` 为参考坐标系，`coordinateScaleX = round(widthPx / 1080, 4)`、`coordinateScaleY = round(heightPx / 2400, 4)`。

---

## 4. 工具用法

```bash
# 校验全部机型档案 + DMI 可用性登记表（CI 门禁入口）
python tools/calibration_collect.py validate
python tools/calibration_collect.py validate --dir vendor/genton/config/calibration

# 由设备 dump 填充档案（默认原地写入；--apply 用设备能力位覆盖档案值）
python tools/calibration_collect.py collect \
    --dump device-dump/sm8550_p0.json \
    --profile vendor/genton/config/calibration/profiles/sm8550_p0.json \
    --samples device-dump/macro-samples.json \
    --apply

# 导出校准矩阵（Markdown）
python tools/calibration_collect.py matrix --out reports/F4.1_机型校准矩阵.md

# 校验全部校准回归样本集（F4.2，黄金值须可由档案复算）
python tools/calibration_collect.py regression-validate

# 导出校准回归样本库（Markdown）
python tools/calibration_collect.py regression-matrix --out reports/F4.2_校准回归样本库.md
```

工具仅依赖 Python 标准库（Python 3.8+），无需 `pip install`。

### 4.1 `validate` 的检查项

1. **schema 校验**：按 `machine-profile.schema.json` 逐字段校验（含 `required` / `const` / `enum` / `pattern` / `minimum` / `maximum` / `additionalProperties:false`）。
2. **登记表自检**：`dmi-availability.json` 的 `toolName` 不得重复、不得缺失。
3. **交叉检查**：
   - `dmiAvailability.registryRef` 指向的文件必须存在；
   - `measurement.status ∈ {measured, verified}` 时 `pendingFields` 必须为空；
   - `measurement.status ∈ {draft, pending_device_measurement}` 时 `pendingFields` 不得为空；
   - 标记为 `stub` 的桥不得在档案 `dmiAvailability.overrides` 中声明 `available: true`。

任一项失败返回非 0，CI 门禁据此终止构建。

### 4.2 `collect` 的能力位覆写规则

读取 `props["ro.genton.motion.capabilities"]`（如 `0x7700037F`）：

- **默认（不加 `--apply`）**：仅与档案记录值**比对**，不一致时输出 `[WARN]`，**不修改**档案；
- **加 `--apply`**：以设备实测值覆盖档案，并按位重新推导：
  - `s7.motionCapabilitiesHex` ← 规范化为 `0x%08X`
  - `s7.sensorHub.aonIsland` ← `CAP_SENSOR_HUB_AON (1<<0)`
  - `s7.tiers.{foreground,deepSleep,sensitiveStop}.enabled` ← `CAP_TIER_* (1<<24/25/26)`
  - `s7.gestures.{shake,flip,swipe}.supported` ← `CAP_GESTURE_* (1<<28/29/30)`

覆盖后 `measurement.status` 由 `recompute_pending()` 重算：仍有待测字段则 `pending_device_measurement`，否则 `measured`，并刷新 `collectedAt` / `collectedBy`。

### 4.3 `matrix` 导出内容

1. **机型总览**：SoC / 分级 / 分辨率 / 密度 / 刷新率 / 能力位 / T1–T3 使能 / 手势 / 状态
2. **宏与降载参数**：坐标缩放 X/Y、支持策略、等待阈值与上下限、捕获降采样、注入帧率上限、渲染降采样
3. **DMI 可用性基线**：implemented（可引用）/ stub（不得引用）
4. **待采集字段汇总**：各档案待测项数与明细

---

## 5. F4.2 校准回归样本库

样本库是**per-机型黄金集**：每个样本集冻结一份机型档案在特定时刻的校准基线，供 H4 真机矩阵的运行器执行。
规范见 [suite.schema.json](../vendor/genton/config/calibration/regression/suite.schema.json)。

### 5.1 样本集结构

| 字段 | 必需 | 说明 |
|------|------|------|
| `suiteId` | 是 | `^[a-z0-9_]{3,48}$`，建议 `calib_regression_<profileId>` |
| `schemaVersion` | 是 | 固定 `"1.0"` |
| `profileRef` | 是 | 相对样本集目录的档案路径，如 `../profiles/sm8550_p0.json` |
| `status` | 是 | `draft` / `pending_device_run` / `verified`（由 H4.1 运行器回写） |
| `runnerRef` | 否 | 执行运行器标识，未就绪时留空 |
| `cases` | 是 | 用例数组，至少 1 条 |

单条用例：`caseId`（建议 `<category>.<维度>`）、`category`、`title`、`priority`（`high/medium/low`）、
`assertion`（`metric` / `op` / `value` / `source`），可选 `onUnsupported`、`params`。

### 5.2 六类 category 语义

| category | 含义 | 允许的 metric | 黄金值来源（`assertion.source`） |
|----------|------|--------------|--------------------------------|
| `coordinate` | 万分比↔像素往返容差 | `roundTripTolerancePx`（`le`） | `display.coordinateSpace.normalizedBase`；`params.normalizedBase` 须与档案基数一致 |
| `replay` | 宏重放成功率（同机/跨机/跨版本） | `successRate`（`ge`） | `macro.successRateTarget.{sameDevice,crossDevice,crossVersion}` |
| `timing` | 关键链路 P95 | `p95LatencyMs`（`le`） | `timing.targets.{commandToVisible,injectFallback,captureToPerception}P95Ms` |
| `downsample` | 三档降载参数 | `captureDownsampleFactor`（`eq`）/ `injectionFrameRateCapFps`（`le`） | `render.captureDownsample.{defaultFactor,minFactor}`、`render.injectionFrameRateCapFps` |
| `dmi_gate` | DMI 桥可用性门控 | `dmiBridgeState`（`eq`，值 `implemented`） | `dmiAvailability.registryRef#<toolName>`（走登记表校验，须 `implemented`） |
| `s7_gate` | 姿态档位/手势/AON 门控 | `motionTierEnabled` / `gestureSupported` / `aonIsland`（均 `eq`） | `s7.tiers.<tier>.enabled`、`s7.gestures.<g>.supported`、`s7.sensorHub.aonIsland` |

### 5.3 黄金值防漂移（`regression-validate`）

除 `dmi_gate`（走登记表）外，**黄金值必须可由 `assertion.source` 指向的档案字段复算得出**。
校验期若样本集与档案不一致即报错——确保样本库不脱离 F4.1 校准基线。任一类别的 metric/op/source 组合不合规亦失败。

### 5.4 能力裁剪与 `onUnsupported`

- 门控类用例（`s7_gate` / `dmi_gate`）黄金值为 `false` 时**必须** `onUnsupported: "skip"`，把"机型不支持"与"失败"区分开；
- 取 `skip` 的用例在真机执行时计入"预期跳过"（如 P1 无 AON 岛 → 深睡档与滑动手势裁剪），不计失败；
- `regression-validate` 汇总输出"执行 N 项 / 预期跳过 M 项"，隔离 CI 门禁中的分母口径。

### 5.5 子命令

```bash
# 校验样本集（黄金值可复算 + 类别约束 + 跳过约定）
python tools/calibration_collect.py regression-validate --dir vendor/genton/config/calibration

# 导出样本库 Markdown（总览 / 分类分布 / 裁剪项 / 阈值来源）
python tools/calibration_collect.py regression-matrix --out reports/F4.2_校准回归样本库.md
```

---

## 6. 档案字段速查

| 段 | 关键字段 | 来源 |
|----|---------|------|
| `identity` | `soc` `tier`（必填）、`deviceModel` 等 | A12.2b 分类 + getprop |
| `display` | `coordinateSpace.normalizedBase`（固定 `10000`）、分辨率/密度/刷新率 | F1.1 万分比归一化 + dumpsys |
| `macro` | `coordinateScaleX/Y`、`supportedStrategies`（0/1/2）、`successRateTarget` | F1.1 `calibrationParams` |
| `timing` | 实测 `*Ms`（可空）+ `targets`（D9 §0 KPI，非实测） | D9 §0 / F1.1 常量 |
| `render` | `captureDownsample`、`injectionFrameRateCapFps`、`renderDownscale` | D9 §6h |
| `dmiAvailability` | `registryRef`、`overrides` | dmi-availability.json |
| `s7` | `motionCapabilitiesHex`、`sensorHub`、`tiers`、`gestures` | A12.2a / A12.2b |
| `measurement` | `status`、`pendingFields`、`sourceNotes` | 采集追溯 |

### 6.1 `measurement.status` 取值

| 值 | 含义 |
|----|------|
| `draft` | 草稿，尚未安排采集 |
| `pending_device_measurement` | 已建档，等待真机采集（`pendingFields` 非空） |
| `measured` | 已完成采集（`pendingFields` 为空） |
| `verified` | 采集并复核通过 |

### 6.2 `sourceNotes` 约定

用于区分**目标值**与**实测值**，防止未测值被误用。示例：

```json
"sourceNotes": {
  "s7": "A12.2b §7.2/§7.3/§8.1（旗舰 P0 能力位 0x7700037F、档位与功耗预算、编译期开关）",
  "timing.targets": "D9 §0 KPI 目标（P95），非实测值",
  "timing.waitInsertThresholdMs/waitClamp*/stepTimeoutMs": "F1.1 实现常量",
  "render": "D9 §6h 三档降载规则"
}
```

> **红线**：`timing.targets`、`macro.successRateTarget`、`s7.tiers.*.powerBudgetMw` 等在样例中为**目标/规范值**，不得当作实测结论；
> 实测值一律写入对应字段并在 `sourceNotes` 标注采集来源，未采集时保持 `null` 且登记 `pendingFields`。

---

## 7. CI 集成

```bash
# Linux / macOS
ci/calibration-check.sh

# Windows
ci\calibration-check.bat
```

脚本执行 4 步：`validate` → `matrix`（`reports/ci/F4.1_机型校准矩阵.md`）→ `regression-validate` → `regression-matrix`（`reports/ci/F4.2_校准回归样本库.md`），任一失败 `exit 1`。
需在构建环境提供 Python 3.8+。

---

## 8. 常见问题

**Q：档案里 `null` 太多，直接填估计值可以吗？**
不可以。未实测字段必须保持 `null` 并登记在 `measurement.pendingFields`；`validate` 会拒绝 `status` 与 `pendingFields` 自相矛盾的档案。

**Q：设备能力位与档案不一致怎么办？**
`collect` 默认只告警不覆盖（防止误采设备污染基线）。确认设备为受支持机型后，加 `--apply` 重采；差异的定位依据是 `A12.2b §7.2/§7.3` 能力位表。

**Q：某个机型没有日历 Provider，怎么登记？**
在 `dmiAvailability.overrides` 中加 `"dmi.calendar.query": { "available": false, "reason": "No Calendar Provider", "verifiedAt": "2026-09-10" }`，不要改全局 `dmi-availability.json`（那是与机型无关的基线）。

**Q：资产可以引用 `stub` 桥吗？**
不可以。`dmi-availability.json` 中 `state: "stub"` 的桥 `execute()` 返回 `RESULT_NOT_SUPPORTED`，调用必失败；`validate` 会拦截把 stub 桥声明为可用的机型档案。

**Q：新增字段需要改 schema 吗？**
需要。`machine-profile.schema.json` 采用 `additionalProperties: false`，任何新字段必须先补 schema，不得临时写入档案。

**Q：某机型不支持深睡姿态档，回归样本集里要删掉该用例吗？**
不删。把黄金值置为 `false` 并加 `onUnsupported: "skip"`，`regression-validate` 会把它计入"预期跳过"而非失败；真机运行器据此判定跳过。
若改成删除用例，就失去"该机型确实不支持"这一基线的可追溯性（参见 `mt6897_p1.suite.json` 的 3 个裁剪项）。
