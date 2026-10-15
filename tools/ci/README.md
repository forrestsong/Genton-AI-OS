# 主线② 构建 CI（W9）

AOSP 树 `aosp_cpday10` 的构建回归与产物验收门禁。

## 快速开始

```bash
# 完整：构建 + 验收（CI 主流程）
bash tools/ci/w9_ci.sh

# 仅验收（已有产物时快速校验，秒级）
bash tools/ci/w9_ci.sh --verify-only

# 自定义
AOSP_ROOT=/root/aosp LUNCH_TARGET=aosp_cpday10-bp2a-eng JOBS=20 bash tools/ci/w9_ci.sh
```

**退出码**：`0` 通过 / `1` 失败 / `2` lunch 失败 —— 可直接用作 CI 门禁。

## 文件

| 文件 | 作用 |
|---|---|
| `w9_ci.sh` | 编排：环境准备 → 构建 → 验收 → 报告 |
| `w9_verify.py` | 验收校验：对照基线检查镜像与源码链路，返回退出码 |

产物报告输出到 `$AOSP/out/ainative-reports/`：
- `build-<时间戳>.log` — 构建日志
- `verify-<时间戳>.json` — 验收明细（机器可读）
- `w9-report-<时间戳>.md` — 汇总报告

## 验收基线

| 检查项 | 基线 | 依据 |
|---|---|---|
| `system.img` | ≥ 953 MB | 实测 1148 MB |
| `vendor.img` | ≥ 28 MB | 实测 40 MB |
| `product.img` | ≥ 190 MB | 实测 303 MB |
| `framework.jar` 中 `com/ainative` 类 | ≥ 480 | 实测 488（AIDL 契约 + 平台类） |
| `services.jar` 中服务实现类 | ≥ 220 | 实测 251 |
| 注册的服务接口 | ≥ 17 | 实测 17 |
| `/vendor/ainative/config` 文件 | ≥ 10 | 实测 10 |
| sepolicy 服务上下文条目 | ≥ 40 | 实测 41 |
| 源码链路（4 项关键修复） | 在位 | R8 keep / SystemServer 注册 / 注册表 / vendor 分区 |

阈值略低于实测值，以便容忍正常波动而不误报。

### 为什么校验「源码链路」

四项关键修复**只存在于源码**，一旦被覆盖或回退，镜像会静默失去功能（正是 R8 裁剪事故的形态）：

1. `proguard.flags` 的 `com.android.server.ainative` keep 规则 —— 缺失则服务被 R8 裁剪
2. `SystemServer.java` 的 `AinativeServiceRegistry.start(` —— 缺失则服务不注册
3. `AinativeServiceRegistry.java` —— 注册表本体
4. `BoardConfig.mk` 的 `TARGET_COPY_OUT_VENDOR := vendor` —— 缺失则 vendor.img 不构建

## 门禁有效性（反向测试）

已实测门禁**不会空转**：

| 反向测试 | 结果 |
|---|---|
| 指向空目录（产物全缺失） | 0/11 通过 → 退出码 1 ✅ |
| 阈值调至 9999（必然不达标） | 12/13 → 退出码 1 ✅ |
| 破坏源码标记 | 12/13 → 退出码 1 ✅ |

## 已知前提与限制

| 前提 | 说明 |
|---|---|
| **lunch 必须用 `bp2a`** | `ap3a` 是 Android 15 配置，会导致平台代码 Java 21 语法编译失败 |
| **`SKIP_ABI_CHECKS=true`** | 开发期绕过；正式发布须生成 ABI 参考转储（见 `patches/mainline2/README.md`） |
| **仅校验静态产物** | 服务能否真机启动成功需 W8 实机验证（`dumpsys \| grep ainative`） |
| **无 `boot.img` 校验** | 树中无内核源，本门禁不检查 boot.img |

## 接入 CI 系统

通用写法（任意支持 shell 的 CI）：

```yaml
# 示意：需自备 AOSP 树与构建环境
- name: AOSP build + acceptance
  run: bash tools/ci/w9_ci.sh
```

注意：AOSP 全量构建耗时长、磁盘占用大（本树 `out/` 约 11 GB，源树 138 GB），
建议在**已同步源码的自托管 runner** 上以增量方式运行，而非每次从零构建。
