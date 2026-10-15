# sample-agent — 三方沙箱联调最小示例（F5.2）

本目录提供一个**最小示例 Agent**（"便签小助手"），用于第三方 Agent 与 B6 沙箱宿主的联调。
它演示了：清单声明（能力最小化）、`ISandboxedAgent` 实现骨架、经网关的安全调用方式。

> ⚠️ **联调占位标注**：以下内容为占位，联调时必须替换/核对（详见 §3）：
> - `signature_ref`：`sha256-<PLACEHOLDER...>` 需替换为 APK 真实签名指纹；
> - `model.endpoints[].domain`：`api.example.com` 需替换为你的模型 API 域名；
> - `requested_capabilities[].capability`：能力名需与当前**策略引擎能力清单**（A7.1/B6.2 5 OP 白名单）核对一致；
> - `ISandboxedAgent` 中的远端模型调用、参数解析：需按你的模型接口实现。

## 目录

| 文件 | 说明 |
|---|---|
| `SandboxManifest.json` | 示例 Agent 的能力与边界声明（可直接被 B6.1 校验器解析） |
| `NotepadMateAgent.kt` | `ISandboxedAgent` 实现骨架 + 网关调用示例（编译骨架，业务逻辑为占位） |

## 1. 示例 Agent 简介

- **能力**：读取掩码 UI 摘要（帮用户记录所见）→ 写入自身记忆（便签）；
- **记忆范围**：`own_only`（只读写自身分区）；
- **出网**：仅 `api.example.com`；
- **预算**：单日 $0.05、单任务 30s、50 次/小时；
- **风险**：全部 low/medium，无 high 能力（无需确认流，演示最小权限）。

## 2. 联调步骤

```bash
# 1) 替换占位（签名指纹 / 模型域名）
# 2) 校验清单
python -c "import json; json.load(open('SandboxManifest.json', encoding='utf-8')); print('JSON OK')"

# 3) 打包 APK：isolatedProcess 服务绑定 ISandboxedAgent
#    （见 docs/F5.1-三方Agent开发包说明.md §4.1）

# 4) 安装后由宿主启动沙箱（startSandbox），走首启授权向导
#    （可先经 F5.2 合规指引 §2 的授权 UI 走查脚本人工验证）

# 5) 运行中观察：
adb shell dumpsys agent_sandbox_manager list   # 沙箱状态
adb shell dumpsys agent_asset_library | grep "sandbox"  # 授权记录
# 审计：隐私仪表盘查看该 Agent 最近做了什么
```

## 3. 联调核对清单（占位 → 真实）

| 占位项 | 联调动作 | 验收标准 |
|---|---|---|
| `signature_ref` | 替换为 APK 签名证书 sha256 指纹 | `startSandbox` 返回 OK（非 `STATUS_UNSIGNED`） |
| `model.endpoints[].domain` | 替换为真实模型域名，并确认已在 `network_domains` | 模型调用出网成功；未在白名单的域被 iptables 阻断 |
| `requested_capabilities` | 与策略引擎能力清单核对 | 授权向导逐项正确展示；未授权能力网关拒绝 |
| `ISandboxedAgent.onUserMessage` | 接真实远端模型 | 会话轮次正常，tool call 经网关回传 |
| `NotepadMateAgent.kt` 中硬编码的参数 | 按你的业务实现 | 动作执行结果经 `onToolResult` 回传 |

## 4. 最小实现骨架要点（见 NotepadMateAgent.kt）

1. 实现 `ISandboxedAgent`（宿主调用你）；
2. 用 `IAgentSandboxGateway` 调 `OP_UI_READ_MASKED`（读掩码 UI）与 `OP_MEMORY_OWN`（写自身记忆）；
3. 所有动作只经网关，**不 bind 任何系统服务**（签名权限拒绝）；
4. 收到 `stop(reason=REVOKED)` 时清理状态、不得重试已撤销操作。

---

**维护**：F 资产生态部（F5.2 三方沙箱联调组）
**版本**：1.0　**更新日期**：2026-10-05
