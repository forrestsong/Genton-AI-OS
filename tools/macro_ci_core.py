#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI Native OS — 宏资产 CI 校验流水线核心工具（F1.2）
================================================================
被 ci/run-macro-ci.sh / ci/run-macro-ci.bat 调用；也可单独执行用于排障。

子命令:
  validate     静态校验黄金样例文件（必填字段 / testType / isGolden / schema 存在性）
  replay       在设备上重放黄金样例并采集结果（--offline 时全部标记 skip）
  gatedisable  按阈值判定自动停用，维护连续失败状态，产出回灌 A8.1 清单（占位调用）
  baseline     版本基线管理（新增 / 漂移检测，--update 采纳新基线）

退出码（与 ci/run-macro-ci.sh / .bat 约定一致）:
  0  通过（无失败、无停用、无漂移）
  1  测试失败或基线漂移（需人工 review，构建应失败）
  2  有宏被自动停用并写入 A8.1 回灌清单（需人工 review）
  3  环境错误（无设备 / 无 adb / 重放基础设施不可用）
  4  配置或 schema 错误（黄金样例缺失 / 字段不合法 / 配置无法解析）
  130 用户中断

对齐文档:
  - docs/macro-testing-specification.md（黄金样例格式 / 结果类型 / 自动停用策略）
  - ci/macro-ci-config.yaml（阈值配置：consecutive_failures / failure_rate / min_samples / required_pass_rate）
  - reports/A8.1_完成报告.md（setAssetStatus / ASSET_STATUS_DISABLED=1 / staged-enable）
"""

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

PIPELINE_NAME = "macro-ci"
STATE_VERSION = 1
FAILURE_WINDOW_MS = 3600_000  # 连续失败统计窗口：1 小时（对齐 A8.1 FAILURE_WINDOW_MS）

DEFAULT_CONFIG = {
    "consecutive_failures": 3,      # 连续失败次数阈值
    "failure_rate": 0.7,            # 失败率阈值（70%）
    "min_samples": 5,               # 高失败率判定的最小样本数
    "required_pass_rate": 1.0,      # 黄金样例必须 100% 通过
}

GOLDEN_REQUIRED_FIELDS = [
    "testCaseId", "macroId", "name", "testType", "isGolden",
    "expectedResult", "timeoutMs",
]
TEST_TYPES = {"smoke", "functional", "regression", "golden"}
RESULT_VALUES = {"pass", "fail", "timeout", "error", "skip"}
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def load_config(path: str) -> dict:
    """读取 ci/macro-ci-config.yaml 中的关键阈值（尽量健壮：缺失时用默认值）。"""
    cfg = dict(DEFAULT_CONFIG)
    if path and Path(path).is_file():
        text = Path(path).read_text(encoding="utf-8")
        for key, conv in (
            ("consecutive_failures", int),
            ("failure_rate", float),
            ("min_samples", int),
            ("required_pass_rate", float),
        ):
            m = re.search(rf"^\s*{key}\s*:\s*([0-9.]+)", text, re.MULTILINE)
            if m:
                try:
                    cfg[key] = conv(m.group(1))
                except ValueError:
                    pass
    return cfg


# ----------------------------------------------------------------------------
# 黄金样例收集与校验
# ----------------------------------------------------------------------------

def find_golden_files(golden_dir: str):
    base = Path(golden_dir)
    if not base.is_dir():
        return []
    files = sorted(base.glob("*.json"))
    return [str(f) for f in files if f.name != "README.md"]


def validate_golden_file(path: str):
    """返回 (ok, errors[])。按 macro-testing-specification §2.3 校验必需字段。"""
    errors = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        return False, [f"JSON 解析失败: {e}"]
    if not isinstance(data, dict):
        return False, ["顶层必须是 JSON 对象"]
    for field in GOLDEN_REQUIRED_FIELDS:
        if field not in data:
            errors.append(f"缺少必需字段: {field}")
    if "testType" in data and data["testType"] not in TEST_TYPES:
        errors.append(f"testType 不合法: {data['testType']}（应为 {sorted(TEST_TYPES)}）")
    if "isGolden" in data and not isinstance(data["isGolden"], bool):
        errors.append("isGolden 必须是布尔值")
    if "timeoutMs" in data and not isinstance(data["timeoutMs"], (int, float)):
        errors.append("timeoutMs 必须是整数（毫秒）")
    if "macroVersion" in data and data["macroVersion"] != "unknown" \
            and not VERSION_RE.match(str(data["macroVersion"])):
        errors.append(f"macroVersion 必须是 SemVer（如 1.2.3），当前: {data.get('macroVersion')}")
    if "expectedResult" in data and not isinstance(data["expectedResult"], dict):
        errors.append("expectedResult 必须是对象")
    return (len(errors) == 0, errors)


def cmd_validate(args) -> int:
    cfg = load_config(args.config)
    files = find_golden_files(args.golden_dir)
    if not files:
        print(f"[CONFIG-ERROR] 黄金样例目录为空或不存在: {args.golden_dir}")
        print("  请先通过 F1.3 样本扩充放入 golden 样例，或 --golden-dir 指向已有目录。")
        return 4
    total, bad = 0, 0
    for f in files:
        total += 1
        ok, errors = validate_golden_file(f)
        if ok:
            print(f"  [OK] {os.path.basename(f)}")
        else:
            bad += 1
            print(f"  [FAIL] {os.path.basename(f)}")
            for e in errors:
                print(f"        - {e}")
    print(f"\n黄金样例静态校验: {total - bad}/{total} 通过"
          f"（required_pass_rate={cfg['required_pass_rate']:.0%}）")
    if bad > 0:
        print("[CONFIG-ERROR] 存在不合法黄金样例，流水线终止。")
        return 4
    return 0


# ----------------------------------------------------------------------------
# 重放（设备模式 / offline）
# ----------------------------------------------------------------------------

def collect_cases(golden_dir: str):
    cases = []
    for f in find_golden_files(golden_dir):
        with open(f, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        cases.append({
            "testCaseId": data.get("testCaseId"),
            "macroId": data.get("macroId"),
            "macroName": data.get("name"),
            "macroVersion": data.get("macroVersion", "unknown"),
            "scriptId": data.get("scriptId") or data.get("macroId"),
            "timeoutMs": data.get("timeoutMs", 30000),
            "file": f,
        })
    return cases


def check_device(adb: str, serial: str):
    """返回 (ok, message)。"""
    try:
        cmd = [adb]
        if serial:
            cmd += ["-s", serial]
        cmd += ["devices"]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            return False, f"adb 不可用: {r.stderr.strip() or r.stdout.strip()}"
        lines = [ln for ln in r.stdout.splitlines() if ln.strip() and "List of devices" not in ln]
        ready = [ln for ln in lines if "device" in ln.split()[-1:]]
        if not ready:
            return False, f"未发现已连接设备（adb devices 输出: {r.stdout.strip()}）"
        return True, ready[0]
    except FileNotFoundError:
        return False, "未找到 adb 可执行文件（请设置 ANDROID_HOME/platform-tools 或 PATH）"
    except Exception as e:
        return False, f"设备检测异常: {e}"


def dispatch_case(adb: str, serial: str, case: dict) -> dict:
    """在设备上重放单个黄金样例。

    依赖 F1.1 MacroPlaybackValidator 的服务端上报：脚本触发
    com.genton.macro.EXECUTE_SCRIPT 广播后，从
    /sdcard/genton/test_reports/<testCaseId>.json 读取结果。
    """
    tc_id = case["testCaseId"]
    script_id = case["scriptId"]
    device_path = f"/sdcard/genton/test_reports/{tc_id}.json"
    base = [adb] + (["-s", serial] if serial else [])

    # 1) 触发重放
    try:
        trigger = base + ["shell", "am", "broadcast",
                          "-a", "com.genton.macro.EXECUTE_SCRIPT",
                          "--es", "scriptId", str(script_id),
                          "--es", "testCaseId", str(tc_id)]
        r = subprocess.run(trigger, capture_output=True, text=True, timeout=120)
    except Exception as e:
        return {"result": "error", "reason": f"adb 触发重放失败: {e}"}

    # 2) 读取结果文件
    result_payload = {}
    try:
        cat = base + ["shell", "cat", device_path]
        p = subprocess.run(cat, capture_output=True, text=True, timeout=30)
        if p.returncode == 0 and p.stdout.strip():
            result_payload = json.loads(p.stdout)
    except Exception:
        result_payload = {}

    result = result_payload.get("result")
    if result in RESULT_VALUES and result not in ("error", "skip"):
        return {
            "result": result,
            "executionTimeMs": result_payload.get("executionTimeMs", 0),
            "failureReason": result_payload.get("failureReason"),
            "failedStepIndex": result_payload.get("failedStepIndex"),
            "screenshotRef": result_payload.get("screenshotRef"),
            "uiSnapshotRef": result_payload.get("uiSnapshotRef"),
            "testedAt": result_payload.get("testedAt") or now_ms(),
        }
    # 3) 结果文件缺失 → 基础设施不可用（不计入自动停用）
    return {
        "result": "error",
        "reason": (f"结果文件缺失: {device_path}（需 F1.1 MacroPlaybackValidator 上报；"
                   f"broadcast 返回码 {r.returncode}）"),
        "testedAt": now_ms(),
    }


def build_report(cases: list, mode: str, cfg: dict) -> dict:
    macros = {}
    for c in cases:
        macros.setdefault(c["macroId"], {
            "macroId": c["macroId"],
            "macroName": c.get("macroName", c["macroId"]),
            "macroVersion": c.get("macroVersion", "unknown"),
            "totalTests": 0, "passed": 0, "failed": 0,
            "timeout": 0, "error": 0, "skipped": 0,
        })
        m = macros[c["macroId"]]
        m["totalTests"] += 1
        key = "skipped" if c["result"] == "skip" else c["result"]
        m[key] = m.get(key, 0) + 1

    macro_list = []
    for m in macros.values():
        if m["failed"] + m["timeout"] > 0:
            m["status"] = "fail"
        elif m["error"] > 0 and m["passed"] == 0:
            m["status"] = "error"
        elif m["skipped"] == m["totalTests"]:
            m["status"] = "skip"
        else:
            m["status"] = "pass"
        macro_list.append(m)

    summary = {
        "totalCases": len(cases),
        "passed": sum(1 for c in cases if c["result"] == "pass"),
        "failed": sum(1 for c in cases if c["result"] in ("fail", "timeout")),
        "error": sum(1 for c in cases if c["result"] == "error"),
        "skipped": sum(1 for c in cases if c["result"] == "skip"),
        "totalMacros": len(macro_list),
        "passedMacros": sum(1 for m in macro_list if m["status"] == "pass"),
        "failedMacros": sum(1 for m in macro_list if m["status"] == "fail"),
        "disabledMacros": 0,
    }
    return {
        "generatedAt": now_iso(),
        "pipeline": PIPELINE_NAME,
        "mode": mode,
        "config": cfg,
        "summary": summary,
        "macros": macro_list,
        "cases": cases,
    }


def write_outputs(out_dir: str, report: dict):
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    report_path = Path(out_dir) / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    write_junit(out_dir, report)
    print(f"[REPORT] {report_path}")


def write_junit(out_dir: str, report: dict):
    s = report["summary"]
    suites = []
    for m in report["macros"]:
        failures = m["failed"] + m["timeout"]
        suites.append(
            f'<testsuite name="{m["macroId"]}" tests="{m["totalTests"]}" failures="{failures}">'
        )
        for c in report["cases"]:
            if c["macroId"] != m["macroId"]:
                continue
            name = c["testCaseId"]
            if c["result"] == "pass":
                suites.append(f'  <testcase name="{name}" classname="{m["macroId"]}"/>')
            elif c["result"] == "skip":
                suites.append(f'  <testcase name="{name}" classname="{m["macroId"]}">'
                              f'<skipped message="{c.get("reason", "skipped")}"/></testcase>')
            else:
                msg = c.get("failureReason") or c.get("reason") or c["result"]
                suites.append(f'  <testcase name="{name}" classname="{m["macroId"]}">'
                              f'<failure message="{msg}"/></testcase>')
        suites.append("</testsuite>")
    body = "\n".join(suites)
    xml = (f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuites tests="{s["totalCases"]}" '
           f'failures="{s["failed"]}" skipped="{s["skipped"]}">\n{body}\n</testsuites>\n')
    (Path(out_dir) / "junit.xml").write_text(xml, encoding="utf-8")


def summarize(report: dict):
    s = report["summary"]
    print("\n" + "=" * 56)
    print(f"宏资产 CI 汇总（mode={report['mode']}）")
    print("=" * 56)
    print(f"  用例总数:     {s['totalCases']}")
    print(f"  通过:         {s['passed']}")
    print(f"  失败(含超时): {s['failed']}")
    print(f"  错误(infra):  {s['error']}")
    print(f"  跳过:         {s['skipped']}")
    print(f"  宏资产:       {s['totalMacros']}（通过 {s['passedMacros']} / 失败 {s['failedMacros']}）")
    for m in report["macros"]:
        if m["status"] != "pass":
            print(f"    - {m['macroId']} v{m['macroVersion']}: {m['status']} "
                  f"(passed={m['passed']}, failed={m['failed']}, error={m['error']}, skipped={m['skipped']})")
    print("=" * 56)


def cmd_replay(args) -> int:
    cfg = load_config(args.config)
    cases = collect_cases(args.golden_dir)
    if not cases:
        print("[CONFIG-ERROR] 无黄金样例可重放。")
        return 4

    if args.offline:
        for c in cases:
            c["result"] = "skip"
            c["reason"] = "offline 模式：未执行设备重放（仅静态校验 + 基线 dry-run）"
        report = build_report(cases, mode="offline", cfg=cfg)
        summarize(report)
        write_outputs(args.out_dir, report)
        return 0

    ok, msg = check_device(args.adb, args.device)
    if not ok:
        print(f"[ENV-ERROR] {msg}")
        for c in cases:
            c["result"] = "error"
            c["reason"] = msg
        report = build_report(cases, mode="device", cfg=cfg)
        summarize(report)
        write_outputs(args.out_dir, report)
        return 3

    print(f"[DEVICE] 已连接: {msg}，开始重放 {len(cases)} 个黄金样例…")
    executed = 0
    for c in cases:
        res = dispatch_case(args.adb, args.device, c)
        c.update(res)
        executed += 1 if res["result"] in ("pass", "fail", "timeout") else 0
        print(f"  [{res['result'].upper():7}] {c['testCaseId']} "
              f"({c['macroId']}) {res.get('reason', '')}")

    report = build_report(cases, mode="device", cfg=cfg)
    summarize(report)
    write_outputs(args.out_dir, report)

    if executed == 0:
        print("[ENV-ERROR] 所有用例均为 error（重放基础设施不可用），不计入自动停用。")
        return 3
    if report["summary"]["failed"] > 0:
        print("[TEST-FAIL] 存在失败/超时用例（未达自动停用阈值或需 review）。")
        return 1
    return 0


# ----------------------------------------------------------------------------
# 自动停用判定 + A8.1 回灌清单
# ----------------------------------------------------------------------------

def load_json(path: str, default):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except Exception:
        return default


def save_json(path: str, data):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def cmd_gatedisable(args) -> int:
    cfg = load_config(args.config)
    report = load_json(args.report, None)
    if not report or "macros" not in report:
        print("[CONFIG-ERROR] 无法读取报告: " + str(args.report))
        return 4

    state = load_json(args.state_file, {"version": STATE_VERSION, "counters": {}})
    counters = state.setdefault("counters", {})
    now = now_ms()
    disabled = []

    for m in report["macros"]:
        macro_id = m["macroId"]
        fails = m.get("failed", 0) + m.get("timeout", 0)
        total = m.get("totalTests", 0)
        passes = m.get("passed", 0)
        cur = counters.get(macro_id, {"consecutiveFailures": 0, "streakStartMs": 0, "lastRunMs": 0})

        # 窗口检查：与上次运行间隔超过 1h 则重置连续失败计数
        if cur.get("lastRunMs") and (now - cur["lastRunMs"]) > FAILURE_WINDOW_MS:
            cur["consecutiveFailures"] = 0
            cur["streakStartMs"] = 0

        if passes > 0:
            cur["consecutiveFailures"] = 0
            cur["streakStartMs"] = 0
        elif fails > 0:
            if cur["streakStartMs"] == 0:
                cur["streakStartMs"] = now
            cur["consecutiveFailures"] += fails
        cur["lastRunMs"] = now
        counters[macro_id] = cur

        failure_rate = fails / total if total else 0.0
        consecutive = cur["consecutiveFailures"]
        samples_ok = total >= cfg["min_samples"]
        reason = None
        if consecutive >= cfg["consecutive_failures"]:
            reason = (f"连续失败 {consecutive} 次 ≥ 阈值 {cfg['consecutive_failures']}"
                      f"（窗口 1h 内）")
        elif samples_ok and failure_rate >= cfg["failure_rate"]:
            reason = (f"失败率 {failure_rate:.0%} ≥ {cfg['failure_rate']:.0%}"
                      f"（样本数 {total} ≥ {cfg['min_samples']}）")
        if reason:
            disabled.append({
                "assetId": macro_id,
                "macroId": macro_id,
                "macroName": m.get("macroName", macro_id),
                "macroVersion": m.get("macroVersion", "unknown"),
                "reason": reason,
                "consecutiveFailures": consecutive,
                "failureRate": round(failure_rate, 3),
                "samples": total,
                "status": "queued",
                "queuedAt": now_iso(),
                # 占位：H1.3 资产 CI 门接入后替换为真实 IPC / 广播。
                # IAgentAssetLibraryService.setAssetStatus(assetId, ASSET_STATUS_DISABLED=1)
                "a81DisableCall": (
                    f"adb shell service call agent_asset_library <TXN_SET_ASSET_STATUS> "
                    f"s16 {macro_id} i32 1   # 占位：需 H1.3 替换为真实 binder 调用"
                ),
            })

    save_json(args.state_file, state)

    queue_path = args.queue_file
    if disabled:
        queue = {"generatedAt": now_iso(), "items": disabled}
        save_json(queue_path, queue)
        print(f"\n[AUTO-DISABLE] {len(disabled)} 个宏触发自动停用，回灌清单: {queue_path}")
        for d in disabled:
            print(f"  - {d['assetId']}: {d['reason']}")
            print(f"    A8.1 回灌（占位）: {d['a81DisableCall']}")
        report["summary"]["disabledMacros"] = len(disabled)
        save_json(args.report, report)
        return 2
    else:
        # 无停用：清空队列文件（避免陈旧停用项被重复消费）
        save_json(queue_path, {"generatedAt": now_iso(), "items": []})
        print("\n[AUTO-DISABLE] 无宏触发自动停用。")
        return 0


# ----------------------------------------------------------------------------
# 版本基线管理
# ----------------------------------------------------------------------------

def cmd_baseline(args) -> int:
    report = load_json(args.report, None)
    if not report or "macros" not in report:
        print("[CONFIG-ERROR] 无法读取报告: " + str(args.report))
        return 4

    baseline = load_json(args.baseline_file, {"generatedAt": now_iso(), "baseline": {}})
    bl = baseline.setdefault("baseline", {})
    now = now_iso()
    drift = []
    new_macros = []

    for m in report["macros"]:
        macro_id = m["macroId"]
        version = m.get("macroVersion", "unknown")
        rec = bl.get(macro_id)
        if rec is None:
            bl[macro_id] = {"version": version, "firstSeenAt": now, "lastSeenAt": now, "status": "new"}
            new_macros.append(macro_id)
        elif rec.get("version") != version:
            old = rec.get("version")
            if args.update:
                rec["version"] = version
                rec["lastSeenAt"] = now
                rec["status"] = "updated"
                print(f"[BASELINE] {macro_id}: {old} → {version}（--update 已采纳）")
            else:
                rec["status"] = "drifted"
                rec["lastSeenAt"] = now
                drift.append((macro_id, old, version))
        else:
            rec["lastSeenAt"] = now
            rec["status"] = "stable"

    baseline["generatedAt"] = now
    save_json(args.baseline_file, baseline)

    print(f"\n[BASELINE] 基线文件: {args.baseline_file}")
    if new_macros:
        print(f"  新增宏（将按 staged-enable 流程观察）: {', '.join(new_macros)}")
    if drift:
        print(f"  版本漂移 {len(drift)} 项（需人工确认是否适配新版本）:")
        for macro_id, old, new in drift:
            print(f"    - {macro_id}: {old} → {new}")
        if not args.update:
            print("  使用 --update-baseline 采纳新版本为基线。")
            return 1
    if not drift and not new_macros:
        print("  基线无变化。")
    return 0


# ----------------------------------------------------------------------------
# 入口
# ----------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="AI Native OS 宏资产 CI 核心工具（F1.2）")
    sub = parser.add_subparsers(dest="command", required=True)

    pv = sub.add_parser("validate", help="静态校验黄金样例")
    pv.add_argument("--golden-dir", required=True)
    pv.add_argument("--config", default="")
    pv.set_defaults(func=cmd_validate)

    pr = sub.add_parser("replay", help="设备重放黄金样例（--offline 跳过）")
    pr.add_argument("--golden-dir", required=True)
    pr.add_argument("--config", default="")
    pr.add_argument("--out-dir", required=True)
    pr.add_argument("--adb", default="adb")
    pr.add_argument("--device", default="")
    pr.add_argument("--offline", action="store_true")
    pr.set_defaults(func=cmd_replay)

    pg = sub.add_parser("gatedisable", help="自动停用判定 + A8.1 回灌清单")
    pg.add_argument("--report", required=True)
    pg.add_argument("--config", default="")
    pg.add_argument("--state-file", required=True)
    pg.add_argument("--queue-file", required=True)
    pg.set_defaults(func=cmd_gatedisable)

    pb = sub.add_parser("baseline", help="版本基线管理")
    pb.add_argument("--report", required=True)
    pb.add_argument("--baseline-file", required=True)
    pb.add_argument("--update", action="store_true")
    pb.set_defaults(func=cmd_baseline)

    args = parser.parse_args()
    try:
        return args.func(args)
    except KeyboardInterrupt:
        print("\n[INTERRUPTED] 用户中断")
        return 130


if __name__ == "__main__":
    sys.exit(main())
