#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
G1.1b 云端决策服务参考实现（Cloud Decision Service Stub）

通用版（非旗舰机型）设备经 CloudBroker `POST /v1/decision` 调用 NeoHorse-Jev-4B 决策模型。
本桩实现：
  1. 请求白名单校验（最小字段强制，FIELD_VIOLATION）
  2. 票据/证明校验占位（G2.1/G2.2 接入点）
  3. 决策模型推理占位（vLLM/SGLang 接入点；prefill-only 返回类型化决策+概率）
  4. usage 计量 + 签名回传（G1.2 对账）
  5. 错误码映射（与端侧 DecisionResult.statusCode 对齐）

契约：docs/cloud/G1.1b-CloudBroker决策接口扩展.md
端侧：com.ainative.decision.IAgentDecisionService（choose/judge/score）
"""
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

# 允许的请求字段白名单（最小字段强制）
ALLOWED_FIELDS = {
    "protocolVersion", "type", "scenario", "state", "imageRef",
    "candidates", "question", "constraints", "privacy", "budget",
    "thresholdVersion",
}
ALLOWED_TYPES = {"choice", "noul", "score"}
# 敏感场景（端侧已拦截，云端设计上不接收）
SENSITIVE_SCENARIOS = {"financial", "health", "account"}

BROKER_SIGNING_KEY = os.environ.get("BROKER_SIGNING_KEY", "dev-broker-key")


def _err(code, message, http):
    return {"error": {"code": code, "message": message,
                      "retryable": False, "statusCode": http}}, http


def validate_request(body):
    """最小字段白名单强制（G1.1 §5 / G1.1b §3.2）"""
    if not isinstance(body, dict):
        return _err("FIELD_VIOLATION", "request must be a JSON object", 422)
    unknown = set(body.keys()) - ALLOWED_FIELDS
    if unknown:
        return _err("FIELD_VIOLATION", f"unknown fields: {sorted(unknown)}", 422)
    if body.get("type") not in ALLOWED_TYPES:
        return _err("FIELD_VIOLATION", f"type must be one of {sorted(ALLOWED_TYPES)}", 422)
    if not body.get("scenario"):
        return _err("FIELD_VIOLATION", "scenario is required", 422)
    if body.get("type") == "choice" and not body.get("candidates"):
        return _err("FIELD_VIOLATION", "choice requires candidates", 422)
    if body.get("type") == "noul" and not body.get("question"):
        return _err("FIELD_VIOLATION", "noul requires question", 422)
    if body.get("scenario") in SENSITIVE_SCENARIOS:
        return _err("CONTENT_POLICY", "sensitive scenario not served by cloud decision", 422)
    return None, None


def check_ticket(headers):
    """票据/证明校验占位（G2.1/G2.2 接入点）"""
    auth = headers.get("authorization", "")
    if not auth.startswith("Bearer "):
        return _err("TICKET_MISSING", "session ticket required", 401)
    ticket = auth.split(" ", 1)[1]
    if len(ticket) < 8:  # 占位：真实校验在 G2.2 票据服务
        return _err("TICKET_INVALID", "invalid ticket", 403)
    return None, None


def _predict_stub(body):
    """决策模型推理占位（vLLM/SGLang 接入点；真实实现替换为 NeoHorse-Jev-4B prefill-only 前向）"""
    typ = body["type"]
    scenario = body["scenario"]
    if typ == "choice":
        cands = [c["key"] if isinstance(c, dict) else c for c in body.get("candidates", [])]
        # 占位：确定性启发式（真实实现为模型概率）
        probs = {c: round(0.6 + 0.05 * i, 3) for i, c in enumerate(cands)}
        total = sum(probs.values())
        probs = {k: round(v / total, 4) for k, v in probs.items()}
        selected = max(probs, key=probs.get)
        confidence = probs[selected]
        return selected, confidence, probs
    if typ == "noul":
        # 占位：基于关键词的启发式（真实实现为模型 P(true)）
        p_true = 0.9 if any(w in body.get("state", "") for w in
                            ["退款", "取消", "投诉", "紧急"]) else 0.2
        return ("true" if p_true >= 0.5 else "false"), round(p_true, 4), {"true": p_true}
    if typ == "score":
        levels = body.get("candidates") or ["低", "中", "高"]
        n = len(levels)
        expected = round((n - 1) * 0.6, 2)  # 占位期望分
        return str(expected), expected, {}


def handle_decision(body, headers):
    """POST /v1/decision 主处理"""
    # 1. 票据校验
    err = check_ticket(headers)
    if err[0] is not None:
        return err
    # 2. 字段白名单
    err = validate_request(body)
    if err[0] is not None:
        return err
    # 3. 预算硬上限（G1.2 接入点）
    budget = body.get("budget", {})
    ceiling = budget.get("costCeilingMicros")
    if ceiling is not None and ceiling <= 0:
        return _err("BUDGET_EXCEEDED", "cost ceiling exceeded", 429)
    # 4. 模型推理
    selected, confidence, probs = _predict_stub(body)
    # 5. usage 计量 + 签名（G1.2 对账：对实际返回字段签名，端侧可验）
    input_tokens = max(1, len(body.get("state", "")) // 2)
    output_tokens = 4 if body["type"] != "score" else 8
    cost_micros = (input_tokens + output_tokens) * 2
    request_id = uuid.uuid4().hex
    ts_ms = int(time.time() * 1000)
    usage = {
        "inputTokens": input_tokens, "outputTokens": output_tokens,
        "totalTokens": input_tokens + output_tokens,
        "costMicros": cost_micros, "ts": ts_ms,
    }
    usage_sig = hmac.new(BROKER_SIGNING_KEY.encode(),
                         json.dumps(usage, sort_keys=True).encode(),
                         hashlib.sha256).hexdigest()
    # 6. 响应（DecisionResult 映射）
    return {
        "requestId": request_id,
        "scenario": body["scenario"],
        "type": body["type"],
        "selected": selected,
        "confidence": confidence,
        "probabilities": probs,
        "modelVersion": 1,
        "backend": "cloud",
        "latencyMicros": 42000,
        "usage": {**usage, "signature": usage_sig},
        "status": {"code": 0, "reason": ""},
    }, 200


# ==================== 本地自测（无 HTTP 框架依赖） ====================

def _selftest():
    passed = 0
    failed = 0

    def check(name, cond):
        nonlocal passed, failed
        if cond:
            passed += 1
            print(f"  [PASS] {name}")
        else:
            failed += 1
            print(f"  [FAIL] {name}")

    ok_headers = {"authorization": "Bearer tkt_valid_session"}

    # 1. choice 正常
    r, code = handle_decision({
        "type": "choice", "scenario": "intent_classify",
        "state": "帮我订明天去北京的机票",
        "candidates": [{"key": "open_app", "desc": "打开应用"},
                       {"key": "query_info", "desc": "查询信息"}],
        "privacy": {"mode": "masked"}, "budget": {"costCeilingMicros": 200},
    }, ok_headers)
    check("choice 返回 200 + DecisionResult",
          code == 200 and r["type"] == "choice" and r["status"]["code"] == 0
          and "probabilities" in r and r["backend"] == "cloud")

    # 2. noul 正常
    r, code = handle_decision({
        "type": "noul", "scenario": "learn_adopt",
        "state": "用户要求退款", "question": "用户是否要求退款？",
        "budget": {"costCeilingMicros": 200},
    }, ok_headers)
    check("noul 返回 P(true)", code == 200 and r["confidence"] >= 0.5)

    # 3. score 正常
    r, code = handle_decision({
        "type": "score", "scenario": "callgate_spam",
        "state": "未知号码推销", "candidates": ["低风险", "中风险", "高风险"],
        "budget": {"costCeilingMicros": 200},
    }, ok_headers)
    check("score 返回期望分", code == 200 and r["type"] == "score")

    # 4. 无票据 → 401
    r, code = handle_decision({"type": "noul", "scenario": "x", "state": "s"},
                              {"authorization": ""})
    check("无票据 → 401 TICKET_MISSING", code == 401)

    # 5. 超白名单字段 → 422
    r, code = handle_decision({"type": "noul", "scenario": "x", "state": "s",
                               "evil": 1}, ok_headers)
    check("超白名单字段 → 422 FIELD_VIOLATION", code == 422)

    # 6. choice 缺 candidates → 422
    r, code = handle_decision({"type": "choice", "scenario": "x", "state": "s"},
                              ok_headers)
    check("choice 缺 candidates → 422", code == 422)

    # 7. 敏感场景 → 422 CONTENT_POLICY
    r, code = handle_decision({"type": "noul", "scenario": "financial",
                               "state": "银行卡", "question": "q"}, ok_headers)
    check("敏感场景 → 422 CONTENT_POLICY", code == 422)

    # 8. 预算超限 → 429
    r, code = handle_decision({"type": "noul", "scenario": "x", "state": "s",
                               "question": "q",
                               "budget": {"costCeilingMicros": 0}}, ok_headers)
    check("预算超限 → 429 BUDGET_EXCEEDED", code == 429)

    # 9. usage 签名存在且可复算（对返回的 usage 字段验签）
    r, _ = handle_decision({"type": "noul", "scenario": "x", "state": "你好",
                            "question": "q"}, ok_headers)
    u = r["usage"]
    signed = {k: u[k] for k in ("inputTokens", "outputTokens", "totalTokens",
                                "costMicros", "ts")}
    expected = hmac.new(BROKER_SIGNING_KEY.encode(),
                        json.dumps(signed, sort_keys=True).encode(),
                        hashlib.sha256).hexdigest()
    check("usage 签名可验（防篡改对账）", u["signature"] == expected)

    print("=" * 60)
    print(f"汇总: PASS {passed} / FAIL {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(_selftest())
