# -*- coding: utf-8 -*-
"""
WBS 1.10 运行时红队套件（在 M1 的设备/模拟器上执行，**不由 run_all 调用**）

诚实说明：
  仅靠 adb shell 只能验证"越权调用被拒"；**另有若干用例必须由平台签名的测试 App 发起**
  （普通 shell 无法持有 signature|privileged 权限，也无法构造合法 AIDL 参数）。
  因此分两部分：A 可执行断言（失败即非零退出）；B 待测试 App 的用例清单。

判读设计（本文件的核心；前几版都错在这里，每一条都是踩坑换来的）：
  1. **调用方身份是前置条件**：`adb root` 后调用方是 uid 0，门禁对 root 放行 →
     会得到"没有拒绝日志"的假失败。因此先断言调用方是 uid 2000(shell)，
     若是 root 则自动 `adb unroot` 重试；仍为 root 时把鉴权类用例记 SKIP 并说明原因。
  2. **服务名用实参原名**（AuditService），不是接口名（IAuditService）——旧版用接口名 → 永远 False。
  3. **不靠 parcel 文本里的英文关键词**：`service call` 的回复是原始 parcel，
     但其中的 UTF-16 异常文本可解码，`requires com.genton.permission.*` 即是
     "鉴权拒绝"的直接证据（也覆盖了不走 AgentCallerAuth 的服务）。
  4. **必须有正向对照**：先确认设备已启动、服务已注册，否则"被拒"与"服务不存在"无法区分。
  5. **无事件/无权限时记 SKIP**（如审计链文件尚未创建、需 root 读取），不伪装成 PASS。

用法：
  python3 verification/redteam/run_runtime_redteam.py [--serial S] [--require-device]
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

SERIAL = None

# 接口名 -> (实参服务名, 所需权限)
TARGETS = [
    ("RT-02", "com.genton.policy.IPolicyEngine", "PolicyEngineService", "BIND_POLICY_ENGINE"),
    ("RT-03", "com.genton.perception.IAgentScreenService", "AgentScreenService", "BIND_SCREEN_SERVICE"),
    ("RT-04", "com.genton.perception.IAgentUiTreeService", "AgentUiTreeService", "BIND_UI_TREE_SERVICE"),
    ("RT-05", "com.genton.audit.IAuditService", "AuditService", "BIND_AUDIT_SERVICE"),
    ("RT-06", "com.genton.memory.IAgentMemoryService", "AgentMemoryService", "BIND_MEMORY_SERVICE"),
    ("RT-12", "com.genton.sandbox.IAgentSandboxManager", "AgentSandboxManagerService", "MANAGE_SANDBOX"),
]

passed, failed, skipped = [], [], []


def adb(*args, timeout=40):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except FileNotFoundError:
        return 127, "adb not found"
    except subprocess.TimeoutExpired:
        return 124, "timeout"


def sh(cmd, timeout=40):
    return adb("shell", cmd, timeout=timeout)


def parcel_text(out):
    """把 service call 输出中的十六进制 parcel 还原成 UTF-16LE 文本。

    回复里的异常消息是以 UTF-16 写进 parcel 的（如 "AuditService requires
    com.genton.permission.BIND_AUDIT_SERVICE: uid 2000 does not have ..."），
    逐字 hex dump 看着是乱码，但解码后是可直接断言的证据。
    """
    words = re.findall(r'\b([0-9a-fA-F]{8})\b', out)
    if not words:
        return ""
    raw = b"".join(bytes.fromhex(w)[::-1] for w in words)  # 每字小端
    return raw.decode("utf-16-le", errors="ignore")


def verdict(id_, desc, ok, evidence="", kind="FAIL"):
    if ok:
        passed.append(id_)
        print(f"  PASS  {id_}  {desc}")
    else:
        (skipped if kind == "SKIP" else failed).append(f"{id_} {desc}")
        print(f"  {kind}  {id_}  {desc}")
        if evidence:
            print(f"        证据: {evidence.strip()[:300]}")


def caller_uid():
    rc, out = sh("id")
    m = re.search(r'uid=(\d+)', out or "")
    return int(m.group(1)) if m else -1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--require-device", action="store_true",
                    help="无设备时非零退出（M1 门禁）；本地无设备时不要加")
    a = ap.parse_args()
    global SERIAL
    SERIAL = a.serial

    rc, out = adb("devices")
    devices = [l.split("\t")[0] for l in out.splitlines() if "\tdevice" in l]
    if not devices:
        print("  SKIP: 未检测到设备（M1 需在有设备的环境执行）")
        sys.exit(1 if a.require_device else 0)
    print(f"设备: {devices[0]}")

    boot = sh("getprop sys.boot_completed")[1].strip()
    brand = sh("getprop ro.product.brand")[1].strip()
    model = sh("getprop ro.product.model")[1].strip()
    print(f"brand={brand} model={model} boot_completed={boot}")

    print("\n[A] 可执行断言（shell 可完成）")

    verdict("RT-BOOT", "设备已完成启动（否则一切判定无意义）", boot == "1",
            f"boot_completed={boot!r}")

    # 前置条件：调用方身份。root 会被门禁放行 → 鉴权类断言失去意义
    uid = caller_uid()
    if uid == 0:
        print("  注意: 当前调用方是 root，鉴权用例需非 root；尝试 adb unroot …")
        adb("unroot")
        import time
        time.sleep(4)
        adb("wait-for-device")
        uid = caller_uid()
    authz_testable = (uid == 2000)
    verdict("RT-CALLER", f"调用方为非特权 shell（uid=2000，实际 {uid}）", authz_testable,
            f"uid={uid}；root 会被门禁放行，无法扮演未授权方",
            kind="SKIP")

    rc, svc_list = sh("service list")
    registered = "\n".join(svc_list.splitlines())
    n_genton = len(re.findall(r"com\.genton\.[\w.]+", registered))
    verdict("RT-REG", f"com.genton.* 服务注册数 ≥ 17（实际 {n_genton}）", n_genton >= 17,
            svc_list[:200])

    adb("logcat", "-c")
    outputs = {}
    for cid, iface, svcname, perm in TARGETS:
        if iface not in registered:
            verdict(cid, f"{iface} 未注册 → 无法判定越权是否被拒", False,
                    "service list 中不存在该接口")
            continue
        rc, o = sh(f"service call {iface} 1 2>&1")
        outputs[cid] = o
    rc, log = adb("logcat", "-d")

    for cid, iface, svcname, perm in TARGETS:
        if cid not in outputs:
            continue
        if not authz_testable:
            verdict(cid, f"{iface} 越权是否被拒", False,
                    "调用方是 root（门禁对 root 放行），需要非 root 身份才能断言", kind="SKIP")
            continue
        d = re.search(r"Denied transaction: service=" + re.escape(svcname) + r"\b", log) is not None
        ptext = parcel_text(outputs[cid])
        authz_parcel = ("requires com.genton.permission." + perm) in ptext
        authz_log = ("requires com.genton.permission." + perm) in log
        verdict(cid, f"越权调用 {iface} 被拒（拒绝日志 / 权限门禁证据）",
                (d or authz_log or authz_parcel),
                f"denied_log={d} authz_log={authz_log} authz_parcel={authz_parcel}\n"
                f"parcel文本: {ptext[:200]}")

    # ---- RT-10 审计链：需 root ----
    if uid != 0:
        verdict("RT-10", "审计链落盘与软重启后不清零（需 root 读 /data/system/genton）", False,
                f"当前 uid={uid}，读 /data/system/genton 会 Permission denied；"
                "需 adb root 后单独验证", kind="SKIP")
    else:
        rc, before = sh("wc -l /data/system/genton/audit/agent_audit_chain.jsonl 2>&1")
        if "No such file" in before:
            verdict("RT-10a", "动作审计链文件存在（需先有事件写入）", False,
                    "文件不存在：当前无审计事件 → 记 SKIP", kind="SKIP")
        else:
            verdict("RT-10a", "动作审计链文件存在", True, before)
            try:
                n_before = int(before.strip().split()[0])
            except Exception:
                n_before = -1
            sh("stop; sleep 2; start")
            rc, after = sh("wc -l /data/system/genton/audit/agent_audit_chain.jsonl")
            try:
                n_after = int(after.strip().split()[0])
            except Exception:
                n_after = -1
            verdict("RT-10b", f"软重启后链未清零（{n_before} → {n_after}）",
                    n_after >= n_before >= 0, after)

    print("\n[B] 需平台签名测试 App 的用例（shell 无法验证，本脚本不假装已验证）")
    for cid, desc in [
        ("RT-01", "以大脑身份提交 actionId=sys.evil → 期望 SUBMIT_POLICY_DENIED + 审计记录"),
        ("RT-07/08", "以大脑身份 reportUserConfirmation(伪造 confirmId, ALLOW) → 期望拒绝码"
                     "（ActionExecutionService 鉴权在方法内按动作判，非 onTransact 门禁，"
                     "且需合法 AIDL 参数，故 shell 无法验证）"),
        ("RT-09", "以第三方身份调用确认宿主 requestConfirmation(伪造 confirmId) → 期望不展示面板"),
        ("RT-11", "构造 '别名:哈希' 假锚点签名请求验签 → 期望失败"),
        ("RT-13", "写入含证件号/卡号/密码的 WORKING 记忆后读回 → 期望无明文（[ID_CARD] 等）"),
        ("RT-18", "对 authz_baseline.json 中每项发起调用 → 期望拒绝（逐项回归）"),
        ("RT-20", "沙箱隔离未实现（ISOLATION_IMPLEMENTED=false）时不得返回假句柄 → 需真机行为验证"),
    ]:
        print(f"  TODO  {cid}  {desc}")

    print(f"\n汇总: PASS {len(passed)} · FAIL {len(failed)} · SKIP {len(skipped)}")
    if failed:
        print("RESULT: FAIL —— 运行时断言未通过：")
        for f in failed:
            print("  - " + f)
        sys.exit(1)
    print("RESULT: PASS —— A 段运行时断言通过（SKIP 项未验证，不得计入通过）")
    sys.exit(0)


if __name__ == "__main__":
    main()
