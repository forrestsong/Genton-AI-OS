# -*- coding: utf-8 -*-
"""忠实复现 ActionRegistryValidator.java 的全部门禁规则"""
import json, re, sys

VALID_EXECUTORS = {"activity","input","query","device_control","notification",
                   "clipboard","telephony","settings","system"}
VALID_RISK = {0,1,2}
VALID_SOURCES = {"trusted","user","any"}
ACTION_NAME = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
CAPABILITY = re.compile(r"^agent\.[a-z_]+$")
AUDIT_EVENT = re.compile(r"^action\.[a-z_]+$")
REQUIRED = ["name","displayName","executorId","riskLevel",
            "requiredCapability","inputSchema","outputSchema","auditEventType"]

def validate(path):
    errors, warnings = [], []
    with open(path, encoding="utf-8") as f:
        reg = json.load(f)
    if "version" not in reg: errors.append("缺少 version 字段")
    if "actions" not in reg:
        errors.append("缺少 actions 字段"); return errors, warnings, 0
    actions = reg["actions"]
    names = set()
    for i, a in enumerate(actions):
        p = f"actions[{i}]"
        for fld in REQUIRED:
            if fld not in a: errors.append(f"{p} 缺少必填字段: {fld}")
        name = a.get("name","")
        if not name: errors.append(f"{p} name 不能为空")
        else:
            if name in names: errors.append(f"{p} name 重复: {name}")
            names.add(name)
            if not ACTION_NAME.match(name): errors.append(f"{p} name 命名规范错误: {name}")
        dn = a.get("displayName","")
        if not dn: errors.append(f"{p} displayName 不能为空")
        elif len(dn) > 64: errors.append(f"{p} displayName 超 64 字符")
        ex = a.get("executorId","")
        if ex not in VALID_EXECUTORS: errors.append(f"{p} executorId 无效: {ex}")
        if "riskLevel" not in a: errors.append(f"{p} 缺少 riskLevel")
        elif a["riskLevel"] not in VALID_RISK: errors.append(f"{p} riskLevel 无效: {a['riskLevel']}")
        cap = a.get("requiredCapability","")
        if not CAPABILITY.match(cap): errors.append(f"{p} requiredCapability 格式错误: {cap}")
        if "inputSource" in a:
            for k,v in a["inputSource"].items():
                if v not in VALID_SOURCES: errors.append(f"{p} inputSource[{k}] 类型无效: {v}")
        if "inputSchema" in a:
            isc = a["inputSchema"]
            if isc.get("type") != "object": errors.append(f"{p} inputSchema.type 必须为 object")
            if "properties" not in isc: errors.append(f"{p} inputSchema 缺少 properties")
        if "outputSchema" in a:
            if a["outputSchema"].get("type") != "object": errors.append(f"{p} outputSchema.type 必须为 object")
        aet = a.get("auditEventType","")
        if not AUDIT_EVENT.match(aet): errors.append(f"{p} auditEventType 格式错误: {aet}")
        if a.get("riskLevel",0) == 2:
            if not a.get("inputSource"): warnings.append(f"{p} 高风险动作建议声明 inputSource")
        if a.get("deprecated",False) and "replacedBy" not in a:
            warnings.append(f"{p} 废弃动作建议指定 replacedBy")
    return errors, warnings, len(actions)

if __name__ == "__main__":
    errs, warns, n = validate(sys.argv[1])
    print("="*60)
    print(f"动作注册表校验报告  |  动作总数: {n}")
    print("="*60)
    print("校验结果:", "通过" if not errs else "失败")
    if errs:
        print(f"\n错误 ({len(errs)}):")
        for e in errs: print("  -", e)
    if warns:
        print(f"\n警告 ({len(warns)}):")
        for w in warns: print("  -", w)
    sys.exit(0 if not errs else 1)
