#!/usr/bin/env python3
"""
AI Native OS - 端云链路端到端（H3.4）

任务编号：H3.4（质量工程部 · 端云链路 e2e）
里程碑  ：A3 · 前置：A9.1, G1.1, G2.1 · 依赖草案：D13
验收项  （对齐 WBS A3 门禁 "H3.4 端云 e2e（证明→Broker→断线降级）过"）：
  1. 证明 → Broker → 回传：设备远程证明成功 → 云 Broker 中转 → 结果回传
  2. 脱敏断言：出端载荷不含 PII（姓名/手机/邮箱/证件），仅最小字段
  3. 断线降级：Broker 不可达 → 本地降级链（模型不可用→规则→显式路径）
  4. 删除传播联动：设备 forget → 云端级联删除 + 删除证明

实现方式：云端桩（AttestationServer / CloudBroker / CloudStore）+ 端侧
模拟器进程内串联。脱敏用纯函数 PiiScrubber 实现。

可独立运行：python3 tools/e2e/test_e2e_cloud.py
退出码：0=全部通过，1=存在失败。
"""

import hashlib
import hmac
import json
import re
import sys
from typing import Any, Dict, List, Optional

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass


# ============================================================
# PII 脱敏纯函数（G1.3 出端脱敏中间件语义）
# ============================================================

PII_PATTERNS = [
    (re.compile(r"1[3-9]\d{9}"), "PHONE"),          # 手机号
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "EMAIL"),
    (re.compile(r"\d{17}[\dXx]"), "ID_CARD"),       # 身份证
]


class PiiScrubber:
    """出端脱敏：PII 剥离 / 字段白名单 / 摘要化"""

    ALLOWED_KEYS = {"intent", "op", "params", "city", "date", "query",
                    "amount", "currency"}

    def scrub(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        out: Dict[str, Any] = {}
        for k, v in payload.items():
            if k not in self.ALLOWED_KEYS:
                continue  # 字段白名单
            out[k] = self._mask(v)
        return out

    def _mask(self, value: Any) -> Any:
        if isinstance(value, dict):
            return self.scrub(value)
        if isinstance(value, list):
            return [self._mask(x) for x in value]
        if isinstance(value, str):
            for pattern, tag in PII_PATTERNS:
                value = pattern.sub(f"[{tag}]", value)
            return value
        return value


# ============================================================
# 云端桩
# ============================================================

class AttestationServer:
    """设备远程证明服务端（G2.1 桩）"""

    def __init__(self):
        self.valid_devices = {"device_001", "device_002"}

    def attest(self, device_id: str, nonce: str) -> Optional[str]:
        """返回设备票据（attestation ticket），未登记设备返回 None"""
        if device_id not in self.valid_devices:
            return None
        payload = f"{device_id}:{nonce}"
        return hmac.new(b"attest-key", payload.encode(),
                        hashlib.sha256).hexdigest()


class CloudBroker:
    """云 Broker（G1.1 桩）：中转请求/响应"""

    def __init__(self):
        self.online = True
        self.log: List[Dict[str, Any]] = []

    def request(self, device_id: str, ticket: str,
                payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        if not self.online:
            return None  # 断线
        if not ticket:
            return {"error": "attestation_required"}
        self.log.append({"device": device_id, "ticket": ticket[:8],
                         "payload": payload})
        return {"ok": True, "echo": payload, "broker_ts": len(self.log)}


class CloudStore:
    """云端存储（G3.1 删除传播桩）"""

    def __init__(self):
        self.data: Dict[str, Dict[str, Any]] = {}
        self.deletion_log: List[Dict[str, Any]] = []

    def put(self, device_id: str, key: str, value: Dict[str, Any]) -> None:
        self.data.setdefault(device_id, {})[key] = value

    def delete_device(self, device_id: str, reason: str) -> List[str]:
        """级联删除：删除该设备全部云端数据，返回删除证明清单"""
        removed = list(self.data.get(device_id, {}).keys())
        if device_id in self.data:
            del self.data[device_id]
        proof = {
            "device_id": device_id, "reason": reason,
            "removed_keys": removed, "deleted_at": len(self.deletion_log),
            "proof": hashlib.sha256(
                json.dumps(sorted(removed)).encode()).hexdigest(),
        }
        self.deletion_log.append(proof)
        return removed


# ============================================================
# 端侧模拟器
# ============================================================

class DeviceSimulator:
    """端侧链路：证明 → Broker → 回传 / 断线降级 / 删除传播"""

    def __init__(self, device_id: str, attest: AttestationServer,
                 broker: CloudBroker, store: CloudStore):
        self.device_id = device_id
        self.attest = attest
        self.broker = broker
        self.store = store
        self.scrubber = PiiScrubber()
        self.local_fallback = {
            "query.weather": {"ok": True, "data": {"city": "local-cache"}},
            "query.calendar": {"ok": True, "data": {"source": "local-rule"}},
        }

    def get_ticket(self) -> Optional[str]:
        return self.attest.attest(self.device_id, nonce="n1")

    def send_query(self, op: str, raw_payload: Dict[str, Any]) -> Dict[str, Any]:
        """证明 → Broker → 回传；断线时本地降级"""
        ticket = self.get_ticket()
        if ticket is None:
            return {"ok": False, "reason": "attestation_failed"}
        # 出端脱敏：PII 剥离 + 最小字段
        scrubbed = self.scrubber.scrub(raw_payload)
        resp = self.broker.request(self.device_id, ticket, scrubbed)
        if resp is None:
            # 断线降级：本地规则/缓存
            if op in self.local_fallback:
                return {"ok": True, "degraded": True, "data": self.local_fallback[op]["data"]}
            return {"ok": False, "degraded": True, "reason": "broker_offline"}
        return {"ok": True, "degraded": False, "data": resp}

    def forget(self, reason: str = "user_forget") -> Dict[str, Any]:
        """设备 forget → 云端级联删除 + 删除证明"""
        removed = self.store.delete_device(self.device_id, reason)
        proof = self.store.deletion_log[-1] if self.store.deletion_log else {}
        return {"removed": removed, "proof": proof.get("proof")}


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

def test_attest_broker_roundtrip(t: Suite, dev: DeviceSimulator):
    print("\n[1/5] 证明 → Broker → 回传")
    t.check(dev.get_ticket() is not None, "设备远程证明成功（票据签发）")
    resp = dev.send_query("query.weather", {"city": "北京", "date": "today"})
    t.check(resp["ok"], "查询请求经 Broker 回传成功")
    t.check(resp["degraded"] is False, "在线路径非降级")
    t.check(len(dev.broker.log) >= 1, "Broker 收到并中转请求")


def test_desensitization(t: Suite, dev: DeviceSimulator):
    print("\n[2/5] 出端脱敏断言")
    raw = {
        "intent": "查询天气",
        "params": {"city": "上海"},
        "contact_name": "张三",            # 不在白名单
        "phone": "13800138000",           # PII
        "email": "zhangsan@example.com",  # PII
        "id_card": "110101199001011234",  # PII
    }
    scrubbed = dev.scrubber.scrub(raw)
    joined = json.dumps(scrubbed, ensure_ascii=False)
    t.check("张三" not in joined, "姓名被剥离（字段白名单）")
    t.check("13800138000" not in joined, "手机号被掩码")
    t.check("zhangsan@example.com" not in joined, "邮箱被掩码")
    t.check("110101199001011234" not in joined, "身份证被掩码")
    t.check("contact_name" not in scrubbed, "非白名单字段被剥离")
    t.check("city" in scrubbed["params"] or "city" in scrubbed,
            "白名单字段保留")
    # Broker 收到的载荷无 PII
    dev.send_query("query.weather", raw)
    broker_payload = dev.broker.log[-1]["payload"]
    bj = json.dumps(broker_payload, ensure_ascii=False)
    t.check("13800138000" not in bj and "张三" not in bj,
            "Broker 实际收到的出端载荷无 PII")


def test_offline_degradation(t: Suite, dev: DeviceSimulator):
    print("\n[3/5] 断线降级")
    dev.broker.online = False
    resp = dev.send_query("query.weather", {"city": "广州"})
    t.check(resp["ok"], "断线时本地降级路径可用")
    t.check(resp["degraded"] is True, "降级标记 degraded=true")
    t.check(resp["data"].get("source") == "local-cache" or
            resp["data"].get("city") == "local-cache",
            "降级数据来自本地缓存/规则")
    # 断线 + 无本地降级项 → 明确失败而非挂死
    resp2 = dev.send_query("query.unknown_remote", {"q": "x"})
    t.check(not resp2["ok"] and resp2.get("degraded") is True,
            "无本地降级项时明确失败（degraded=true）")
    dev.broker.online = True
    resp3 = dev.send_query("query.weather", {"city": "广州"})
    t.check(resp3["ok"] and resp3["degraded"] is False,
            "恢复在线后回到 Broker 路径")


def test_deletion_propagation(t: Suite, dev: DeviceSimulator):
    print("\n[4/5] 删除传播联动")
    dev.store.put(dev.device_id, "k1", {"v": 1})
    dev.store.put(dev.device_id, "k2", {"v": 2})
    dev.store.put("device_002", "k3", {"v": 3})  # 他人数据不受影响
    r = dev.forget(reason="user_forget")
    t.check_eq(sorted(r["removed"]), ["k1", "k2"], "级联删除本设备全部数据")
    t.check(dev.device_id not in dev.store.data, "云端本设备数据已清空")
    t.check(dev.store.data.get("device_002", {}).get("k3") == {"v": 3},
            "其他设备数据不受影响")
    t.check(r["proof"] is not None, "返回删除证明")
    t.check(len(dev.store.deletion_log) >= 1, "删除传播审计已记录")


def test_attestation_failure(t: Suite):
    print("\n[5/5] 证明失败兜底")
    attest = AttestationServer()
    broker = CloudBroker()
    store = CloudStore()
    evil = DeviceSimulator("device_evil", attest, broker, store)
    t.check(evil.get_ticket() is None, "未登记设备证明失败")
    resp = evil.send_query("query.weather", {"city": "x"})
    t.check(not resp["ok"] and resp["reason"] == "attestation_failed",
            "证明失败 → 拒绝请求（attestation_failed）")
    t.check(len(broker.log) == 0, "证明失败请求未到达 Broker")


def main() -> int:
    print("=" * 62)
    print("H3.4 端云链路端到端")
    print("=" * 62)
    t = Suite()
    attest = AttestationServer()
    broker = CloudBroker()
    store = CloudStore()
    dev = DeviceSimulator("device_001", attest, broker, store)
    test_attest_broker_roundtrip(t, dev)
    test_desensitization(t, dev)
    test_offline_degradation(t, dev)
    test_deletion_propagation(t, dev)
    test_attestation_failure(t)

    print("\n" + "=" * 62)
    print(f"汇总：PASS {t.passed} / FAIL {t.failed}")
    print("=" * 62)
    if t.failed == 0:
        print("✓ H3.4 全部通过")
        return 0
    print(f"✗ {t.failed} 个断言失败")
    return 1


if __name__ == "__main__":
    sys.exit(main())
