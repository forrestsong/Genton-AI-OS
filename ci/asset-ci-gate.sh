#!/bin/bash
# ============================================================
# AI Native OS - 资产 CI 门（asset-ci-gate）
#
# 任务编号：H1.3（质量工程部 · 资产 CI 门）
# 里程碑  ：A2 · 前置：F1.2, F2.1 · 依赖草案：D11
# 验收项  ：
#   1. 宏 / DMI / Skill schema 存在性
#   2. execution 字段强制（DMI: paths.*.*.execution；Skill: 顶层 execution；
#      宏: execution 步骤块）
#   3. 作者侧校验（authorName / reviewPolicy / publisher 等）
#   4. 失败自动停用联调占位（写 auto-disable 指令文件，对接 A8.1 资产库）
#
# 用法：
#   ./ci/asset-ci-gate.sh                      # 全量资产目录扫描
#   ./ci/asset-ci-gate.sh --schema-dir docs/schemas
#   ./ci/asset-ci-gate.sh --allow-missing-schema macro   # 灰度豁免（staged-enable）
#
# 退出码：0=通过；1=存在阻断项；2=环境错误
# ============================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPORTS_DIR="$PROJECT_ROOT/reports/ci"
AUTO_DISABLE_DIR="$REPORTS_DIR/auto-disable"
mkdir -p "$AUTO_DISABLE_DIR"

SCHEMA_DIR="${AINATIVE_SCHEMA_DIR:-$PROJECT_ROOT/docs/schemas}"
export AINATIVE_SCHEMA_DIR="$SCHEMA_DIR"
export AINATIVE_PROJECT_ROOT="$PROJECT_ROOT"
export AINATIVE_ALLOW_MISSING_SCHEMA=""
for arg in "$@"; do
    case "$arg" in
        --allow-missing-schema) export AINATIVE_ALLOW_MISSING_SCHEMA="1" ;;
        --schema-dir=*) export AINATIVE_SCHEMA_DIR="${arg#--schema-dir=}" ;;
    esac
done

python3 - <<'PYEOF'
import json
import os
import re
import sys

# Windows 控制台 GBK 兼容
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PROJECT_ROOT = os.environ.get(
    "AINATIVE_PROJECT_ROOT",
    os.path.dirname(os.path.dirname(os.path.abspath(__file__ or "."))))
SCHEMA_DIR = os.environ.get("AINATIVE_SCHEMA_DIR",
                            os.path.join(PROJECT_ROOT, "docs", "schemas"))
AUTO_DISABLE_DIR = os.path.join(PROJECT_ROOT, "reports", "ci", "auto-disable")
os.makedirs(AUTO_DISABLE_DIR, exist_ok=True)

ALLOW_MISSING = os.environ.get("AINATIVE_ALLOW_MISSING_SCHEMA") == "1"

PASS = 0
FAIL = 0
WARN = 0

def ok(msg):
    global PASS; PASS += 1
    print(f"  [PASS] {msg}")

def bad(msg):
    global FAIL; FAIL += 1
    print(f"  [FAIL] {msg}")

def warn(msg):
    global WARN; WARN += 1
    print(f"  [WARN] {msg}")

# ============================================================
# 1. schema 存在性
# ============================================================
print("[1/4] Schema 存在性")
SCHEMAS = {
    "macro": ["macro-asset-schema.json", "macro.schema.json"],
    "dmi":   ["dmi-asset-schema.json"],
    "skill": ["skill-asset-schema.json"],
}
found_schemas = {}
for kind, names in SCHEMAS.items():
    path = None
    for n in names:
        p = os.path.join(SCHEMA_DIR, n)
        if os.path.exists(p):
            path = p
            break
    if path:
        found_schemas[kind] = path
        ok(f"{kind} schema 存在: {os.path.relpath(path, PROJECT_ROOT)}")
    else:
        msg = f"{kind} schema 缺失（期望位于 docs/schemas/: {', '.join(names)}）"
        if ALLOW_MISSING:
            warn(f"{msg}（灰度豁免）")
        else:
            bad(msg)

# ============================================================
# 2. 资产扫描目录（可扩展）
# ============================================================
ASSET_DIRS = [
    os.path.join(PROJECT_ROOT, "docs", "examples"),
    os.path.join(PROJECT_ROOT, "vendor", "ainative", "assets"),
    os.path.join(PROJECT_ROOT, "macros"),
    os.path.join(PROJECT_ROOT, "packages", "apps"),
    os.path.join(PROJECT_ROOT, "overlay", "frameworks", "base", "res", "ainative"),
]
ASSET_FILES = []
for d in ASSET_DIRS:
    if not os.path.isdir(d):
        continue
    for root, _dirs, files in os.walk(d):
        for f in files:
            low = f.lower()
            if low.endswith((".json", ".yaml", ".yml")):
                full = os.path.join(root, f)
                if ("dmi" in low or "macro" in low or "skill" in low
                        or "asset" in low or "manifest" in low):
                    ASSET_FILES.append(full)

# ============================================================
# 3. execution 字段强制 + 作者侧校验
# ============================================================
print(f"[2/4] 资产 execution 字段强制（扫描到 {len(ASSET_FILES)} 个候选资产文件）")
if not ASSET_FILES:
    warn("未扫描到资产文件，创建 docs/examples/ 下的资产后可启用真实校验")

def classify_asset(path, data):
    low = os.path.basename(path).lower()
    if "dmi" in low:
        return "dmi"
    if "skill" in low or (isinstance(data, dict) and "skill" in data and "execution" in data):
        return "skill"
    if "macro" in low or (isinstance(data, dict) and "macro" in data):
        return "macro"
    if isinstance(data, dict) and "openapi" in data:
        return "dmi"
    return None

def check_execution_json(path, data):
    """JSON 资产的 execution 字段强制 + 作者侧校验"""
    kind = classify_asset(path, data)
    rel = os.path.relpath(path, PROJECT_ROOT)
    if kind is None:
        return
    if kind == "dmi":
        info = data.get("info", {})
        xa = info.get("x-ainative", {}) if isinstance(info, dict) else {}
        if not xa.get("authorName") and not xa.get("reviewPolicy"):
            bad(f"{rel}: DMI 作者侧字段缺失（info.x-ainative.authorName/reviewPolicy）")
        else:
            ok(f"{rel}: DMI 作者侧字段齐备")
        paths = data.get("paths", {})
        if not isinstance(paths, dict) or not paths:
            bad(f"{rel}: DMI 缺少 paths")
            return
        missing = []
        for p, ops in paths.items():
            if not isinstance(ops, dict):
                continue
            for method, op in ops.items():
                if method.lower() not in ("get", "post", "put", "delete", "patch"):
                    continue
                if not isinstance(op, dict) or "execution" not in op:
                    missing.append(f"{p} {method.upper()}")
        if missing:
            bad(f"{rel}: 以下操作缺少 execution 字段: {', '.join(missing[:5])}")
            _write_disable(rel, f"dmi operation missing execution: {missing[:3]}")
        else:
            ok(f"{rel}: DMI 全部操作均含 execution 字段")
    elif kind == "skill":
        if "execution" not in data:
            bad(f"{rel}: Skill 缺少顶层 execution 字段")
            _write_disable(rel, "skill missing top-level execution")
            return
        execo = data["execution"]
        if not isinstance(execo, dict) or "steps" not in execo:
            bad(f"{rel}: Skill execution.steps 缺失")
            _write_disable(rel, "skill execution.steps missing")
        else:
            ok(f"{rel}: Skill execution.steps 齐备（{len(execo['steps'])} 步）")
        meta = data.get("metadata", {})
        if not isinstance(meta, dict) or not meta.get("capability"):
            bad(f"{rel}: Skill metadata.capability 缺失")
        else:
            ok(f"{rel}: Skill capability={meta['capability']}")
    elif kind == "macro":
        execo = data.get("execution") if isinstance(data, dict) else None
        steps = execo.get("steps") if isinstance(execo, dict) else None
        if not isinstance(steps, list) or len(steps) == 0:
            bad(f"{rel}: 宏资产缺少 execution.steps（不可执行宏）")
            _write_disable(rel, "macro missing execution.steps")
        else:
            ok(f"{rel}: 宏 execution.steps 齐备（{len(steps)} 步）")

def check_execution_yaml(path):
    """YAML 资产：无 yaml 库时做操作级 execution 覆盖检查（门禁）"""
    rel = os.path.relpath(path, PROJECT_ROOT)
    try:
        text = open(path, "r", encoding="utf-8").read()
    except OSError:
        bad(f"{rel}: 无法读取")
        return
    # 统计路径操作（get/post/put/delete/patch 顶层方法行）与 execution 块
    op_count = len(re.findall(r"^\s{2,}(get|post|put|delete|patch)\s*:",
                              text, re.M))
    exec_count = len(re.findall(r"^\s{2,}execution\s*:", text, re.M))
    if op_count == 0:
        ok(f"{rel}: 未检测到路径操作（视为非资产 YAML）")
        return
    if exec_count >= op_count:
        ok(f"{rel}: 全部 {op_count} 个操作均含 execution 字段")
    else:
        bad(f"{rel}: 仅 {exec_count}/{op_count} 个操作含 execution 字段"
            f"（缺失 {op_count - exec_count} 个）")
        _write_disable(rel,
                       f"yaml asset missing execution on {op_count - exec_count} ops")

def _write_disable(asset_rel, reason):
    """失败自动停用联调占位：写 auto-disable 指令文件（对接 A8.1 资产库回灌）"""
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", asset_rel)
    directive = {
        "asset": asset_rel,
        "action": "auto_disable",
        "reason": reason,
        "stage": "ci_gate",
        "target": "agent_asset_library",   # A8.1 资产库回灌
        "callback": "POST /assets/{id}/disable",
    }
    out = os.path.join(AUTO_DISABLE_DIR, safe + ".json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(directive, f, ensure_ascii=False, indent=2)
    print(f"        - auto-disable directive: "
          f"{os.path.relpath(out, PROJECT_ROOT)}")

for path in sorted(ASSET_FILES):
    low = os.path.basename(path).lower()
    if low.endswith(".json"):
        try:
            data = json.load(open(path, "r", encoding="utf-8"))
            check_execution_json(path, data)
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            bad(f"{os.path.relpath(path, PROJECT_ROOT)}: JSON 解析失败（{e}）")
    elif low.endswith((".yaml", ".yml")):
        check_execution_yaml(path)

# ============================================================
# 4. 作者侧校验汇总（DMI / Skill）
# ============================================================
print("[3/4] 作者侧校验（authorName / reviewPolicy / publisher）")
author_issues = 0
for path in sorted(ASSET_FILES):
    if not path.lower().endswith(".json"):
        continue
    try:
        data = json.load(open(path, "r", encoding="utf-8"))
    except Exception:
        continue
    kind = classify_asset(path, data)
    if kind == "dmi":
        xa = (data.get("info", {}) or {}).get("x-ainative", {}) or {}
        if not xa.get("publisher"):
            bad(f"{os.path.relpath(path, PROJECT_ROOT)}: DMI 缺 publisher")
            author_issues += 1
    elif kind == "skill":
        skill = data.get("skill", {}) or {}
        if not skill.get("publisher"):
            bad(f"{os.path.relpath(path, PROJECT_ROOT)}: Skill 缺 publisher")
            author_issues += 1
if author_issues == 0 and ASSET_FILES:
    ok("作者侧校验全部通过")

# ============================================================
# 汇总
# ============================================================
print("[4/4] 汇总")
print(f"  PASS={PASS}  FAIL={FAIL}  WARN={WARN}")
if FAIL > 0:
    print(f"[FAIL] 资产 CI 门存在 {FAIL} 个阻断项（auto-disable 指令见 reports/ci/auto-disable/）")
    sys.exit(1)
print("[PASS] 资产 CI 门通过")
sys.exit(0)
PYEOF
exit $?
