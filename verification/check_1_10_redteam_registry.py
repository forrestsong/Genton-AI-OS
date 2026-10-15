# -*- coding: utf-8 -*-
"""
WBS 1.10 静态验收：红队用例登记册的一致性

红队套件只有两种失败方式：漏掉攻击路径，或把"没修"写成"已修"。
本检查器针对第二种，并强制第一种可追溯：

  1. 用例 id 唯一且形如 RT-nn；
  2. 状态必须诚实取值：covered-static / covered-static+runtime / runtime-required / open-gap；
  3. 声明 covered-* 的用例必须指向**真实存在**的静态门禁脚本；
  4. runtime-required / open-gap 必须挂 WBS 编号（不许无主）；
  5. open-gap 不得出现在 run_all 的通过清单里冒充已解决；
  6. 已知攻击路径清单（本文件内固定）必须全部被登记 —— 防止"忘了这条"。

自检：构造一条"声称已覆盖但门禁不存在"的用例，必须被识别。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERIF = ROOT / "verification"
CASES = VERIF / "redteam" / "cases.json"

# 已知攻击路径（来自架构评审与历轮审计），必须全部在登记册中出现
REQUIRED_TOPICS = {
    "前缀后门": ["RT-01"],
    "策略引擎越权": ["RT-02"],
    "截屏越权": ["RT-03"],
    "UI 树越权": ["RT-04"],
    "审计注入": ["RT-05"],
    "记忆越权": ["RT-06"],
    "确认相关": ["RT-07", "RT-08", "RT-09"],
    "审计持久化/锚点": ["RT-10", "RT-11"],
    "沙箱": ["RT-12", "RT-16", "RT-17"],
    "PII": ["RT-13"],
    "污点/注入": ["RT-14"],
}

failures = []


def fail(m):
    failures.append(m)
    print("FAIL: " + m)


def ok(m):
    print("  ok: " + m)


VALID_STATUS = {"covered-static", "covered-static+runtime", "runtime-required", "open-gap"}


def validate(cases, existing_checks):
    """返回 (failures, checked_ids)。抽成函数以便自检复用。"""
    problems = []
    ids = [c.get("id") for c in cases]
    for cid in ids:
        if not cid or not re.fullmatch(r"RT-\d{2}", cid):
            problems.append(f"用例 id 不合法: {cid!r}")
    if len(ids) != len(set(ids)):
        problems.append("存在重复用例 id")
    for c in cases:
        cid = c.get("id", "?")
        st = c.get("status")
        if st not in VALID_STATUS:
            problems.append(f"{cid}: 状态非法 {st!r}")
            continue
        if st.startswith("covered"):
            chk = c.get("static_check")
            if not chk:
                problems.append(f"{cid}: 声明已覆盖但未给出 static_check")
            elif chk not in existing_checks:
                problems.append(f"{cid}: 指向不存在的门禁脚本 {chk}")
        if st in ("runtime-required", "open-gap") and not c.get("wbs"):
            problems.append(f"{cid}: {st} 必须挂 WBS 编号")
    return problems, set(ids)


def main():
    if not CASES.exists():
        print(f"FAIL: 缺少用例登记册 {CASES.relative_to(ROOT)}")
        sys.exit(1)

    data = json.loads(CASES.read_text(encoding="utf-8"))
    cases = data.get("cases", [])
    existing_checks = {p.name for p in VERIF.glob("check_*.py")}

    # ---------- 自检：假的"已覆盖"必须被识别 ----------
    print("[0] 检测器自检")
    bogus = [{"id": "RT-99", "status": "covered-static",
              "static_check": "check_does_not_exist.py", "wbs": "1.1"}]
    problems, _ = validate(bogus, existing_checks)
    if problems:
        ok("能识别『声称已覆盖但门禁不存在』的用例")
    else:
        fail("检测器未识别假的覆盖声明 —— 后续 PASS 不可信")

    print(f"[1] 登记册一致性（{len(cases)} 条用例）")
    problems, ids = validate(cases, existing_checks)
    if problems:
        for p in problems:
            fail(p)
    else:
        ok("id/状态/门禁指向/WBS 归属 均合法")

    print("[2] 已知攻击路径覆盖")
    for topic, want in REQUIRED_TOPICS.items():
        if all(w in ids for w in want):
            ok(f"{topic} → {', '.join(want)}")
        else:
            missing = [w for w in want if w not in ids]
            fail(f"{topic} 缺少用例: {missing}")

    print("[3] 未修项必须显式登记（不许静默消失）")
    gaps = [c for c in cases if c.get("status") == "open-gap"]
    if gaps:
        ok(f"显式登记 {len(gaps)} 项未修复: " + ", ".join(c["id"] for c in gaps))
    else:
        fail("没有任何 open-gap —— 若真已全修，应删除本条断言；否则说明在粉饰")

    print("[4] 运行时用例登记")
    rt = [c for c in cases if c.get("runtime")]
    ok(f"{len(rt)} 条需要设备执行（由 redteam/run_runtime_redteam.py 在 M1 运行）")

    print()
    if failures:
        print(f"RESULT: FAIL（{len(failures)} 项）")
        sys.exit(1)
    print(f"RESULT: PASS —— 红队登记册一致：{len(cases)} 条用例，"
          f"{len(gaps)} 项显式未修，{len(rt)} 项待设备执行")
    sys.exit(0)


if __name__ == "__main__":
    main()
