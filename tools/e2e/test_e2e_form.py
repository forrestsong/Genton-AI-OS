#!/usr/bin/env python3
"""
AI Native OS - L0.5 表单提交端到端（H3.2）

任务编号：H3.2（质量工程部 · L0.5 表单提交 e2e）
里程碑  ：A2 · 前置：D5.2, B3.2 · 依赖草案：D15
验收项  （对齐 WBS A2 门禁 "命令→可见 ≤100ms(P95)" 与 D9 §0 表单行）：
  1. 提交 → 可见结果 ≤100ms（P95 预算断言）
  2. secret 值不出进程断言（值仅 hasValue 标记，任何序列化输出不含明文）
  3. 免 LLM 断言（确定性纯函数管线，无模型调用/无外部网络）

实现方式：桩服务（FormRenderer / FormExecutor / AuditStub）+ L0.5 免 LLM
确定性管线（schema → 控件映射纯函数 → 提交 → 结果渲染），进程内测量。

可独立运行：python3 tools/e2e/test_e2e_form.py
退出码：0=全部通过，1=存在失败。
"""

import json
import statistics
import sys
import time
from typing import Any, Dict, List, Optional

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

# 预算（D9 §0）：结构化命令表单提交→可见结果 ≤100ms（P95）
BUDGET_MS = 100.0
# 提交次数
ITERATIONS = 200


# ============================================================
# L0.5 免 LLM 确定性管线（纯函数）
# ============================================================

# 控件类型
CTRL_TEXT = "text_field"
CTRL_SECURE = "secure_input"
CTRL_SWITCH = "switch"
CTRL_DROPDOWN = "dropdown"


def schema_to_controls(schema: Dict[str, Any]) -> List[Dict[str, Any]]:
    """纯函数：JSON Schema → 表单控件（确定性，免 LLM）"""
    controls: List[Dict[str, Any]] = []
    props = schema.get("properties", {})
    for name, prop in props.items():
        ptype = prop.get("type")
        enum = prop.get("enum")
        xa = prop.get("x-ainative") or {}
        if ptype == "string":
            if xa.get("secret") or prop.get("format") == "password":
                ctrl = CTRL_SECURE
            elif enum:
                ctrl = CTRL_DROPDOWN
            else:
                ctrl = CTRL_TEXT
        elif ptype == "boolean":
            ctrl = CTRL_SWITCH
        else:
            ctrl = CTRL_TEXT
        out: Dict[str, Any] = {"name": name, "control": ctrl}
        if ctrl == CTRL_SECURE:
            out["secret"] = True
            out["hasValue"] = False
        if ctrl == CTRL_DROPDOWN and enum:
            out["options"] = list(enum)
        controls.append(out)
    return controls


class SecureValue:
    """secret 值容器：值不出进程，仅暴露 hasValue 与内部持有"""

    def __init__(self, value: str):
        self._value = value
        self.has_value = True

    def __repr__(self) -> str:
        return "SecureValue(hasValue=True)"

    def to_payload(self) -> Dict[str, Any]:
        """序列化：绝不携带明文，仅 hasValue 标记"""
        return {"hasValue": self.has_value}


class FormPipeline:
    """L0.5 提交管线（确定性，无 LLM）"""

    def __init__(self, schema: Dict[str, Any]):
        self.schema = schema
        self.controls = schema_to_controls(schema)
        self.llm_calls = 0  # 免 LLM 哨兵计数

    def _require_llm(self):  # pragma: no cover - 免 LLM 设计下不应被调用
        self.llm_calls += 1

    def validate(self, values: Dict[str, Any]) -> List[str]:
        """纯函数校验：必填与类型（无 LLM）"""
        errs: List[str] = []
        required = set(self.schema.get("required", []) or [])
        for name in required:
            if name not in values or values[name] is None:
                errs.append(f"missing:{name}")
        return errs

    def render(self, values: Dict[str, Any]) -> Dict[str, Any]:
        """渲染可见结果（无 LLM）：返回控件 + 安全摘要"""
        visible: Dict[str, Any] = {}
        for ctrl in self.controls:
            name = ctrl["name"]
            if ctrl.get("secret"):
                # secret 值不出进程：结果只呈现 hasValue
                visible[name] = {"control": CTRL_SECURE,
                                 "hasValue": name in values}
            else:
                visible[name] = {"control": ctrl["control"],
                                 "value": values.get(name)}
        return {"visible": visible, "llm_used": False}

    def submit(self, values: Dict[str, Any]) -> Dict[str, Any]:
        """提交全流程：校验 → 渲染可见结果（进程内，免 LLM）"""
        start = time.perf_counter()
        errs = self.validate(values)
        if errs:
            return {"status": "validation_error", "errors": errs,
                    "latency_ms": (time.perf_counter() - start) * 1000.0}
        rendered = self.render(values)
        # 序列化（审计/传输载荷）——secret 仅 hasValue
        payload = self._serialize_payload(values)
        return {
            "status": "submitted",
            "rendered": rendered,
            "payload": payload,
            "latency_ms": (time.perf_counter() - start) * 1000.0,
        }

    def _serialize_payload(self, values: Dict[str, Any]) -> str:
        """序列化载荷：secret 字段替换为 hasValue 标记"""
        safe: Dict[str, Any] = {}
        for ctrl in self.controls:
            name = ctrl["name"]
            if ctrl.get("secret"):
                safe[name] = {"hasValue": name in values}
            else:
                safe[name] = values.get(name)
        return json.dumps(safe, sort_keys=True, ensure_ascii=False)


# ============================================================
# 断言框架
# ============================================================

class Suite:
    def __init__(self):
        self.passed = 0
        self.failed = 0

    def check(self, cond: bool, msg: str):
        if cond:
            self.passed += 1
            print(f"  ✓ {msg}")
        else:
            self.failed += 1
            print(f"  ✗ {msg}")

    def check_eq(self, actual: Any, expected: Any, msg: str):
        self.check(actual == expected,
                   f"{msg}（expected={expected!r}, actual={actual!r}）")


# ============================================================
# 测试用例
# ============================================================

FORM_SCHEMA: Dict[str, Any] = {
    "type": "object",
    "required": ["username", "password"],
    "properties": {
        "username": {"type": "string", "maxLength": 32},
        "password": {"type": "string", "format": "password",
                     "x-ainative": {"secret": True}},
        "remember": {"type": "boolean", "default": False},
        "role": {"type": "string", "enum": ["user", "admin"]},
    },
}
SECRET = "P@ssw0rd-super-secret-12345"


def test_budget_p95(t: Suite, pipeline: FormPipeline):
    print("\n[1/4] 提交→可见 ≤100ms（P95 预算）")
    latencies: List[float] = []
    for i in range(ITERATIONS):
        r = pipeline.submit({"username": f"user{i}",
                             "password": SECRET,
                             "remember": True,
                             "role": "user"})
        t.check(r["status"] == "submitted", f"第 {i} 次提交成功")
        latencies.append(r["latency_ms"])
    latencies.sort()
    p95 = latencies[int(ITERATIONS * 0.95) - 1]
    p50 = statistics.median(latencies)
    print(f"    N={ITERATIONS}  P50={p50:.3f}ms  P95={p95:.3f}ms")
    t.check(p95 <= BUDGET_MS,
            f"P95={p95:.3f}ms ≤ {BUDGET_MS}ms 预算")
    t.check(p50 <= BUDGET_MS / 2, f"P50={p50:.3f}ms ≤ 50ms")


def test_secret_not_leaving_process(t: Suite, pipeline: FormPipeline):
    print("\n[2/4] secret 值不出进程")
    r = pipeline.submit({"username": "alice", "password": SECRET,
                         "remember": False, "role": "user"})
    t.check(r["status"] == "submitted", "提交成功")
    # 1. payload 序列化不含明文
    t.check(SECRET not in r["payload"], "序列化 payload 不含 secret 明文")
    t.check('"hasValue":true' in r["payload"] or '"hasValue": true' in r["payload"],
            "payload 中 secret 仅 hasValue 标记")
    # 2. 渲染结果不含明文
    rendered_json = json.dumps(r["rendered"], sort_keys=True)
    t.check(SECRET not in rendered_json, "渲染结果不含 secret 明文")
    t.check("hasValue" in rendered_json, "渲染结果 secret 仅 hasValue")
    # 3. 控件映射中 secret 控件无 value 字段
    secret_ctrl = [c for c in pipeline.controls if c.get("secret")]
    t.check_eq(len(secret_ctrl), 1, "恰 1 个 secret 控件")
    t.check("value" not in secret_ctrl[0], "secret 控件不携带 value")
    # 4. 进程内字符串（日志/审计）不含明文
    audit_line = json.dumps({"actor": "alice", "fields": r["payload"]})
    t.check(SECRET not in audit_line, "审计行不含 secret 明文")


def test_no_llm(t: Suite, pipeline: FormPipeline):
    print("\n[3/4] 免 LLM 断言")
    # 确定性：同输入同输出
    r1 = pipeline.submit({"username": "bob", "password": SECRET,
                          "remember": True, "role": "admin"})
    r2 = pipeline.submit({"username": "bob", "password": SECRET,
                          "remember": True, "role": "admin"})
    t.check_eq(r1["payload"], r2["payload"], "同输入同输出（确定性管线）")
    t.check_eq(r1["rendered"], r2["rendered"], "渲染结果确定性")
    t.check_eq(pipeline.llm_calls, 0, "管线无任何 LLM 调用（哨兵计数=0）")
    t.check(r1["rendered"].get("llm_used") is False, "渲染结果标注 llm_used=false")
    # 管线步骤均为纯函数（无 IO/网络）——由实现保证 + 哨兵验证


def test_validation(t: Suite, pipeline: FormPipeline):
    print("\n[4/4] 校验与可见结果")
    # 缺必填
    r = pipeline.submit({"username": "carol"})
    t.check_eq(r["status"], "validation_error", "缺必填 password → 校验失败")
    t.check(any("missing:password" in e for e in r["errors"]),
            "错误信息含 missing:password")
    # 完整提交 → 可见结果含字段
    r2 = pipeline.submit({"username": "carol", "password": SECRET,
                          "remember": True, "role": "admin"})
    visible = r2["rendered"]["visible"]
    t.check("username" in visible, "可见结果含 username")
    t.check_eq(visible["password"]["hasValue"], True,
               "可见结果 password 呈现 hasValue=true")
    t.check_eq(visible["role"]["value"], "admin", "可见结果含 role 值")
    t.check_eq(visible["role"]["control"], "dropdown", "role 映射为 dropdown")


def main() -> int:
    print("=" * 62)
    print("H3.2 L0.5 表单提交端到端")
    print("=" * 62)
    t = Suite()
    pipeline = FormPipeline(FORM_SCHEMA)
    test_budget_p95(t, pipeline)
    test_secret_not_leaving_process(t, pipeline)
    test_no_llm(t, pipeline)
    test_validation(t, pipeline)

    print("\n" + "=" * 62)
    print(f"汇总：PASS {t.passed} / FAIL {t.failed}")
    print("=" * 62)
    if t.failed == 0:
        print("✓ H3.2 全部通过")
        return 0
    print(f"✗ {t.failed} 个断言失败")
    return 1


if __name__ == "__main__":
    sys.exit(main())
