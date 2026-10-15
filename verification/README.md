# 验收模板与验证脚本（WBS 0.5）

> 规则只有一条：**任何"完成"，必须附一条别人能复跑的脚本；没有脚本即计为"未验证"。**
> 报告、截图、关键词日志只能作为**辅助**证据，不能作为通过依据。

## 1. 怎么用

```powershell
# 在仓库根运行全部检查（任一失败 → 非零退出码）
python verification/run_all.py
```

单个检查也可以独立运行，例如：

```powershell
python verification/check_1_1_no_prefix_bypass.py
python verification/check_no_secrets_in_git.py
```

## 2. 怎么加一条新检查

1. 新建 `verification/check_<WBS编号>_<主题>.py`；
2. 脚本必须：失败时 `print` 具体证据（文件:行号）并 `sys.exit(1)`；成功时打印一行 `PASS` 后 `sys.exit(0)`；
3. 在 `verification/run_all.py` 的 `CHECKS` 列表里登记；
4. 在对应 WBS 条目的"验收标准"里引用该脚本路径。

## 3. 证据分级（沿用评审口径）

| 级别 | 形式 | 能否作为通过依据 |
|---|---|---|
| A 硬证据 | 可复跑脚本 + 原始日志 + 非零退出码 | 能 |
| B 辅助证据 | 编译退出码、dumpsys/service list 输出、截图 | 仅辅助 |
| C 不构成证据 | 报告文字、"已实现"勾选、关键词 grep | 不能 |

## 4. 当前已登记的检查

| 脚本 | 覆盖 WBS | 断言内容 |
|---|---|---|
| `check_build_tree_parity.py` | 0.2 / 移植 | **归档 ≠ 构建树即 FAIL**：在 WSL 构建树 `/root/aosp`（`com.genton`）中逐条复验安全不变量。构建树不可达时 SKIP 并显式声明"无法验证"，绝不静默通过 |
| `check_precompile_symbols.py` | 移植 / 3.3 前置 | **编译前符号自洽**：同一作用域不得有重复方法定义（区分重载与匿名内部类）、移植代码引用的字段/方法必须存在、关键安全签名必须成立。发现过真实缺陷：移植引入的 `scheduleConfirmTimeout` 与树内原有方法重复（编译必失败） |
| `check_permission_declarations.py` | 1.3 / 运行时 | **权限声明一致性 + 未接线 manifest 片段**。两次真实事故的静态化：① `enforceCallingPermission("X")` 引用的权限若无人声明，等价于"拒绝所有人"——静态门禁全绿、编译 0 错误都发现不了，直到设备上 SystemUI 每 2 秒被拒；② `core/res/AndroidManifest_permissions.xml` 写了声明却未被 `Android.bp` 引用 → 死配置，声明从未生效 |
| `check_1_1_no_prefix_bypass.py` | 1.1 / 1.2a | 内部动作身份不得按 `sys.`/`cmd.` 前缀判定；白名单 + 平台签名调用方校验存在；内部动作强制审计；taint 校验 fail-closed；无未声明字段 |
| `check_1_3_binder_authz.py` | 1.3 | 枚举全部已暴露 Binder 方法的调用方鉴权状态（含类级 onTransact 鉴权）；对 `verification/authz_baseline.json` 棘轮：**新增未鉴权方法即 FAIL，基线过期未收紧也 FAIL** |
| `check_1_4_sepolicy_consistency.py` | 1.4 | 注册服务 ↔ service_contexts ↔ .te 类型声明 ↔ 策略语句语法 四者一致；对 `verification/sepolicy_baseline.json` 棘轮 |
| `check_1_5_confirmation_chain.py` | 1.5a | 确认回灌两道门禁（调用方权限 + confirmId HMAC 复验）且必须先于消费；确认宿主展示前必须真实验签；策略引擎复验不得消费确认 |
| `check_1_6_audit_persistence.py` | 1.6a | 动作审计链必须落盘 + fsync + 启动恢复并校验连续性；锚点管理器必须已实例化；锚点必须用真实 Signature 签名、无密钥时 fail-closed、不得再出现"别名+哈希"假签名 |
| `check_1_8_sandbox_honesty.py` | 1.8a | 隔离未实现时 `startSandbox` 必须 fail-closed，不得发放 pid/uid = -1 的假句柄；Binder 面须鉴权；须显式声明"清单签名未校验""不得暴露为 AIDL" |
| `check_1_9_memory_pii.py` | 1.9a | 高风险 PII（证件号/银行卡/凭据赋值）必须掩码而非仅标记；必须有"高风险未掩码即拒绝"的 fail-closed 不变量；落库内容必须取掩码后字段；长期记忆策略不得放宽 |
| `check_1_10_redteam_registry.py` | 1.10a | 红队用例登记册一致性：id 合法唯一、状态诚实、声称已覆盖必须指向真实门禁、未修项必须显式登记且挂 WBS 编号、已知攻击路径不得漏登 |
| `check_3_1_patch_stack.py` | 3.1a | **真执行** `patches/apply_patches.py --dry-run`：必须有可部署载荷、错误为 0、冲突副本被全局遮蔽；反模式断言（静默 `return 0`、恒报成功、无显式 target 就写入）；部署基线只允许减少 |
| `check_3_2_build_config.py` | 3.2a | 构建配置自洽：`.mk` 引用必须能解析（**在 repo / overlay / patches 三种根上依次解析**）；同名 `.mk` 多副本必须显式声明权威版本，禁止未声明二义性；已知缺口入基线且只允许减少 |
| `check_no_secrets_in_git.py` | 1.7 | 版本库中不得存在任何私钥材料 |

## 4.1 红队套件（WBS 1.10）

* `verification/redteam/cases.json` —— **用例登记册**：18 条攻击路径，每条标注状态与归属（`covered-static` / `runtime-required` / `open-gap`）。
* `verification/check_1_10_redteam_registry.py` —— 登记册门禁（已纳入 `run_all`）。
* `verification/redteam/run_runtime_redteam.py` —— **运行时套件，需设备**，**不由 `run_all` 调用**：
  ```powershell
  python verification/redteam/run_runtime_redteam.py --require-device
  ```
  它分两段：A 段是 shell 可完成的断言（越权调用被拒、链落盘与软重启不清零等）；
  B 段是需要**平台签名测试 App** 的用例，只打印为 M1 交付要求，不假装已验证。

**状态口径**：`open-gap` 表示"已知未修复"，当前 4 项（RT-14 污点自证、RT-15 双审计链、RT-16 沙箱无隔离、RT-17 幽灵服务）。
它们不会因为"没写进报告"而消失——门禁强制它们留在登记册里。

### 基线棘轮（ratchet）机制

1.3 的验收标准是"未鉴权 Binder 方法数 = 0"。在达不到 0 之前，用 `verification/authz_baseline.json` 记录**当前未鉴权清单**：

* 出现**新的**未鉴权方法 → FAIL（安全回归）；
* 基线中的条目已被修复或已消失 → 也 FAIL（基线必须同步收紧，否则指标会长期虚高）。

加鉴权后收紧基线：`python verification/check_1_3_binder_authz.py --write-baseline`

查看完整清单：`python verification/check_1_3_binder_authz.py --report`

## 5. 已知限制（诚实记录）

* 本目录的检查是**静态断言**，不是编译或运行时验证。
  `ActionExecutionService` 等平台服务类的真正编译验证需要 AOSP 树，
  属于 WBS 3.3（真实 Soong 编译进 CI）的门禁项，当前**尚未**具备。
* 因此 1.1 / 1.2a 的当前状态应记为"**静态检查通过，编译与运行时验证待 M1**"，
  不得记为"已完成"。
