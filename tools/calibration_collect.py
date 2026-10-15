#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""F4.1/F4.2 机型校准库工具：采集 / 校验 / 矩阵导出。

仅依赖 Python 标准库（CI 可直接运行，无需 pip）。

子命令：
  validate             按 machine-profile.schema.json 校验机型的校准档案
  collect              由设备 dump（getprop/dumpsys 汇总）填充校准档案字段
  matrix               导出机型校准矩阵（Markdown）供人工评审与 CI 归档
  regression-validate  校验校准回归样本集（F4.2，per-机型黄金集）
  regression-matrix    导出校准回归样本库（Markdown）

用法示例：
  python tools/calibration_collect.py validate
  python tools/calibration_collect.py validate --dir vendor/ainative/config/calibration
  python tools/calibration_collect.py collect --dump device-dump.json \\
      --profile vendor/ainative/config/calibration/profiles/sm8550_p0.json --apply
  python tools/calibration_collect.py matrix --out reports/F4.1_机型校准矩阵.md
  python tools/calibration_collect.py regression-validate
  python tools/calibration_collect.py regression-matrix --out reports/F4.2_校准回归样本库.md

设备 dump 采集方式（真机）：
  adb shell getprop                                   > props.txt
  adb shell dumpsys display                            > display.txt
  adb shell settings get secure user_setup_complete    > （可选）
  dump JSON 结构见 docs/MACHINE_CALIBRATION_GUIDE.md §3
"""

import argparse
import json
import os
import re
import sys

# MotionHub 能力位（对齐 frameworks/base/services/core/java/com/android/server/ainative/MotionHubService.java）
CAP_SENSOR_HUB_AON = 1 << 0
CAP_TIER_FOREGROUND = 1 << 24
CAP_TIER_DEEP_SLEEP = 1 << 25
CAP_TIER_SENSITIVE_STOP = 1 << 26
CAP_GESTURE_SHAKE = 1 << 28
CAP_GESTURE_FLIP = 1 << 29
CAP_GESTURE_SWIPE = 1 << 30

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
DEFAULT_CALIBRATION_DIR = os.path.join(REPO_ROOT, "vendor", "ainative", "config", "calibration")
DEFAULT_SCHEMA = os.path.join(DEFAULT_CALIBRATION_DIR, "machine-profile.schema.json")

TYPE_MAP = {
    "object": dict,
    "array": list,
    "string": str,
    "integer": int,
    "number": (int, float),
    "boolean": bool,
    "null": type(None),
}


# ---------------------------------------------------------------------------
# 极简 JSON Schema 校验（覆盖 machine-profile.schema.json 所用子集）
# ---------------------------------------------------------------------------
def _type_ok(value, type_name):
    if type_name == "integer":
        # JSON 中 1.0 视为 integer（避免 data 生成器差异导致误报）
        return isinstance(value, int) and not isinstance(value, bool) or (
            isinstance(value, float) and value.is_integer()
        )
    if type_name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if type_name == "boolean":
        return isinstance(value, bool)
    expected = TYPE_MAP.get(type_name)
    if expected is None:
        return True
    if expected is dict or expected is list or expected is str:
        return isinstance(value, expected)
    return isinstance(value, expected)


def validate_node(value, schema, root, path, errors):
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/"):
            errors.append("%s: 不支持的 $ref: %s" % (path, ref))
            return
        target = root
        for token in ref[2:].split("/"):
            target = target.get(token, {})
        validate_node(value, target, root, path, errors)
        return

    if "const" in schema and value != schema["const"]:
        errors.append("%s: 期望常量 %r，实际 %r" % (path, schema["const"], value))
        return

    if "enum" in schema and value not in schema["enum"]:
        errors.append("%s: 取值 %r 不在枚举 %r 中" % (path, value, schema["enum"]))

    if "type" in schema:
        allowed = schema["type"]
        allowed = allowed if isinstance(allowed, list) else [allowed]
        if not any(_type_ok(value, t) for t in allowed):
            errors.append("%s: 类型不匹配，期望 %r，实际 %s" % (path, allowed, type(value).__name__))
            return

    if isinstance(value, str) and "pattern" in schema:
        if not re.search(schema["pattern"], value):
            errors.append("%s: 值 %r 不匹配 pattern %s" % (path, value, schema["pattern"]))

    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            errors.append("%s: %r < minimum %r" % (path, value, schema["minimum"]))
        if "maximum" in schema and value > schema["maximum"]:
            errors.append("%s: %r > maximum %r" % (path, value, schema["maximum"]))

    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                errors.append("%s: 缺少必需字段 %s" % (path, key))
        props = schema.get("properties", {})
        additional = schema.get("additionalProperties", True)
        for key, sub in value.items():
            child = "%s.%s" % (path, key) if path else key
            if key in props:
                validate_node(sub, props[key], root, child, errors)
            elif additional is False:
                errors.append("%s: 不允许的字段（additionalProperties=false）" % child)
            elif isinstance(additional, dict):
                validate_node(sub, additional, root, child, errors)

    if isinstance(value, list) and isinstance(schema.get("items"), dict):
        for idx, item in enumerate(value):
            validate_node(item, schema["items"], root, "%s[%d]" % (path, idx), errors)


def load_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def iter_profiles(calibration_dir):
    profiles_dir = os.path.join(calibration_dir, "profiles")
    if not os.path.isdir(profiles_dir):
        return []
    return sorted(
        os.path.join(profiles_dir, name)
        for name in os.listdir(profiles_dir)
        if name.endswith(".json")
    )


# ---------------------------------------------------------------------------
# validate
# ---------------------------------------------------------------------------
def cmd_validate(args):
    schema_path = args.schema or DEFAULT_SCHEMA
    calibration_dir = args.dir or DEFAULT_CALIBRATION_DIR

    if not os.path.isfile(schema_path):
        print("[FAIL] 找不到 schema: %s" % schema_path)
        return 1
    schema = load_json(schema_path)

    # 1) DMI 可用性登记表自检
    errors_total = 0
    availability_path = os.path.join(calibration_dir, "dmi-availability.json")
    if os.path.isfile(availability_path):
        availability = load_json(availability_path)
        seen = set()
        for bridge in availability.get("bridges", []):
            tool = bridge.get("toolName")
            if not tool:
                print("[FAIL] dmi-availability.json: 存在缺少 toolName 的条目")
                errors_total += 1
            elif tool in seen:
                print("[FAIL] dmi-availability.json: toolName 重复: %s" % tool)
                errors_total += 1
            seen.add(tool)
        print("[OK]   dmi-availability.json: %d 条桥登记，toolName 唯一" % len(seen))
    else:
        print("[WARN] 未找到 dmi-availability.json: %s" % availability_path)

    # 2) 机型档案逐个校验
    profiles = iter_profiles(calibration_dir)
    if not profiles:
        print("[FAIL] 未找到任何机型档案: %s/profiles" % calibration_dir)
        return 1

    for path in profiles:
        rel = os.path.relpath(path, REPO_ROOT)
        errors = []
        try:
            profile = load_json(path)
        except ValueError as exc:
            print("[FAIL] %s: JSON 解析失败: %s" % (rel, exc))
            errors_total += 1
            continue
        validate_node(profile, schema, schema, "", errors)

        # 交叉检查：registryRef 指向的可用性登记表必须存在
        registry_ref = (profile.get("dmiAvailability") or {}).get("registryRef")
        if registry_ref:
            target = os.path.normpath(os.path.join(os.path.dirname(path), registry_ref))
            if not os.path.isfile(target):
                errors.append("dmiAvailability.registryRef 指向的文件不存在: %s" % registry_ref)

        # 交叉检查：pendingFields 不得与 measurement.status 自相矛盾
        status = (profile.get("measurement") or {}).get("status")
        pending = (profile.get("measurement") or {}).get("pendingFields") or []
        if status in ("measured", "verified") and pending:
            errors.append(
                "measurement.status=%s 但仍有 %d 个待测字段" % (status, len(pending))
            )
        if status in ("draft", "pending_device_measurement") and not pending:
            errors.append("measurement.status=%s 但 pendingFields 为空" % status)

        # 交叉检查：stub 桥不得被机型档案显式标记为 available
        if os.path.isfile(availability_path):
            stub = {
                b.get("toolName")
                for b in load_json(availability_path).get("bridges", [])
                if b.get("state") == "stub"
            }
            overrides = (profile.get("dmiAvailability") or {}).get("overrides") or {}
            for tool, cfg in overrides.items():
                if tool in stub and cfg.get("available"):
                    errors.append(
                        "dmiAvailability.overrides.%s: stub 桥不应声明 available=true" % tool
                    )

        if errors:
            print("[FAIL] %s" % rel)
            for err in errors:
                print("        - %s" % err)
            errors_total += len(errors)
        else:
            print("[OK]   %s (status=%s, pending=%d)" % (rel, status, len(pending)))

    return 1 if errors_total else 0


# ---------------------------------------------------------------------------
# collect
# ---------------------------------------------------------------------------
# 注意：identity.soc / socModel 为人工维护的机型分级取值（对齐 A12.2b 能力位表），
# 不由设备属性覆盖（ro.board.platform 为平台代号如 kalama，与 SoC 型号如 SM8550 不等价）。
PROP_KEYS = {
    "ro.product.model": ("identity", "deviceModel"),
    "ro.product.manufacturer": ("identity", "deviceManufacturer"),
    "ro.build.version.release": ("identity", "androidVersion"),
    "ro.build.fingerprint": ("identity", "buildFingerprint"),
}


def _set(profile, section, key, value):
    if value is None:
        return
    profile.setdefault(section, {})[key] = value


def recompute_pending(profile):
    """按 schema 约定重算待测字段（仅覆盖本工具可采集的字段）。"""
    checks = [
        ("identity.deviceModel", profile["identity"].get("deviceModel")),
        ("identity.deviceManufacturer", profile["identity"].get("deviceManufacturer")),
        ("identity.androidVersion", profile["identity"].get("androidVersion")),
        ("identity.sdkVersion", profile["identity"].get("sdkVersion")),
        ("identity.buildFingerprint", profile["identity"].get("buildFingerprint")),
        ("display.widthPx", profile["display"].get("widthPx")),
        ("display.heightPx", profile["display"].get("heightPx")),
        ("display.densityDpi", profile["display"].get("densityDpi")),
        ("display.refreshRateHz", profile["display"].get("refreshRateHz")),
        ("display.nativeRefreshRates", profile["display"].get("nativeRefreshRates")),
        ("macro.coordinateScaleX", profile["macro"].get("coordinateScaleX")),
        ("macro.coordinateScaleY", profile["macro"].get("coordinateScaleY")),
        ("timing.tapInjectMs", profile["timing"].get("tapInjectMs")),
        ("timing.swipeInjectMs", profile["timing"].get("swipeInjectMs")),
        ("timing.perceptionDetectMs", profile["timing"].get("perceptionDetectMs")),
        ("timing.legacyInjectFallbackMs", profile["timing"].get("legacyInjectFallbackMs")),
        ("render.renderDownscale.supported", profile["render"]["renderDownscale"].get("supported")),
        ("s7.cameraShutterSensor.available", profile["s7"]["cameraShutterSensor"].get("available")),
        ("s7.privacySwitch.available", profile["s7"]["privacySwitch"].get("available")),
    ]
    known = set((profile["measurement"].get("pendingFields") or []))
    pending = [field for field, value in checks if value in (None, [], {})]
    extra = sorted(f for f in known if f not in {name for name, _ in checks})
    pending.extend(extra)
    profile["measurement"]["pendingFields"] = pending
    return pending


def cmd_collect(args):
    dump = load_json(args.dump)
    profile = load_json(args.profile)

    props = dump.get("props") or {}
    display = dump.get("display") or {}

    for prop, (section, key) in PROP_KEYS.items():
        if prop in props:
            _set(profile, section, key, props[prop])
    sdk = props.get("ro.build.version.sdk")
    if sdk is not None and str(sdk).isdigit():
        _set(profile, "identity", "sdkVersion", int(sdk))

    for key in ("widthPx", "heightPx", "densityDpi"):
        if key in display:
            _set(profile, "display", key, int(display[key]))
    if "refreshRateHz" in display:
        _set(profile, "display", "refreshRateHz", float(display["refreshRateHz"]))
    if display.get("nativeRefreshRates"):
        profile["display"]["nativeRefreshRates"] = [float(r) for r in display["nativeRefreshRates"]]

    # 坐标缩放因子：以 1080x2400 密度的参考坐标系为基准
    width, height = profile["display"].get("widthPx"), profile["display"].get("heightPx")
    if width and height:
        _set(profile, "macro", "coordinateScaleX", round(width / 1080.0, 4))
        _set(profile, "macro", "coordinateScaleY", round(height / 2400.0, 4))

    # 时序：由宏录制样本聚合（可选）
    samples_path = args.samples or dump.get("macroSamples")
    if samples_path and os.path.isfile(samples_path):
        stats = aggregate_macro_samples(load_json(samples_path))
        if stats.get("tapInjectMs") is not None:
            _set(profile, "timing", "tapInjectMs", stats["tapInjectMs"])
        if stats.get("swipeInjectMs") is not None:
            _set(profile, "timing", "swipeInjectMs", stats["swipeInjectMs"])
        print("[INFO] 已由宏录制样本聚合时序: %s" % json.dumps(stats, ensure_ascii=False))

    # S7：能力位与 profile 记录值的一致性核对
    cap_hex = props.get("ro.ainative.motion.capabilities")
    warnings = []
    if cap_hex:
        try:
            caps = int(str(cap_hex), 0)
        except ValueError:
            warnings.append("ro.ainative.motion.capabilities 无法解析为整数: %s" % cap_hex)
            caps = None
        if caps is not None:
            normalized = "0x%08X" % caps
            recorded = profile["s7"].get("motionCapabilitiesHex")
            if recorded and recorded.upper() != normalized.upper():
                warnings.append(
                    "能力位不一致：档案=%s，设备=%s（以是否 --apply 决定是否覆盖）"
                    % (recorded, normalized)
                )
            if args.apply:
                profile["s7"]["motionCapabilitiesHex"] = normalized
                profile["s7"]["sensorHub"]["aonIsland"] = bool(caps & CAP_SENSOR_HUB_AON)
                profile["s7"]["tiers"]["foreground"]["enabled"] = bool(caps & CAP_TIER_FOREGROUND)
                profile["s7"]["tiers"]["deepSleep"]["enabled"] = bool(caps & CAP_TIER_DEEP_SLEEP)
                profile["s7"]["tiers"]["sensitiveStop"]["enabled"] = bool(
                    caps & CAP_TIER_SENSITIVE_STOP
                )
                profile["s7"]["gestures"]["shake"]["supported"] = bool(caps & CAP_GESTURE_SHAKE)
                profile["s7"]["gestures"]["flip"]["supported"] = bool(caps & CAP_GESTURE_FLIP)
                profile["s7"]["gestures"]["swipe"]["supported"] = bool(caps & CAP_GESTURE_SWIPE)

    if dump.get("serialMasked"):
        profile["measurement"]["deviceSerialMasked"] = dump["serialMasked"]

    pending = recompute_pending(profile)
    profile["measurement"]["collectedAt"] = dump.get("collectedAt")
    profile["measurement"]["collectedBy"] = dump.get("collectedBy")
    profile["measurement"]["status"] = "measured" if not pending else "pending_device_measurement"

    print("[INFO] 待测字段剩余 %d 项" % len(pending))
    for warning in warnings:
        print("[WARN] %s" % warning)

    out_path = args.out or args.profile
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(profile, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print("[OK]   已写入 %s（status=%s）" % (os.path.relpath(out_path, REPO_ROOT),
                                             profile["measurement"]["status"]))
    return 0


def aggregate_macro_samples(samples):
    """由宏录制样本估算注入耗时：steps[].{type,durationMs,timestamps}。

    tap: durationMs 取点击按下到抬起；swipe: durationMs 为滑动时长。
    返回各类型耗时中位数（毫秒，取整）。
    """
    taps, swipes = [], []
    for recording in samples.get("recordings", []):
        for step in recording.get("steps", []):
            duration = step.get("durationMs")
            if not isinstance(duration, (int, float)):
                continue
            if step.get("type") == 0:  # TAP
                taps.append(float(duration))
            elif step.get("type") == 1:  # SWIPE
                swipes.append(float(duration))

    def median(values):
        if not values:
            return None
        values = sorted(values)
        mid = len(values) // 2
        if len(values) % 2:
            return int(round(values[mid]))
        return int(round((values[mid - 1] + values[mid]) / 2.0))

    return {"tapInjectMs": median(taps), "swipeInjectMs": median(swipes),
            "tapSamples": len(taps), "swipeSamples": len(swipes)}


# ---------------------------------------------------------------------------
# matrix
# ---------------------------------------------------------------------------
def cmd_matrix(args):
    calibration_dir = args.dir or DEFAULT_CALIBRATION_DIR
    profiles = iter_profiles(calibration_dir)
    if not profiles:
        print("[FAIL] 未找到机型档案")
        return 1

    availability_path = os.path.join(calibration_dir, "dmi-availability.json")
    availability = load_json(availability_path) if os.path.isfile(availability_path) else {}
    state_by_tool = {b.get("toolName"): b.get("state") for b in availability.get("bridges", [])}
    implemented = sorted(t for t, s in state_by_tool.items() if s == "implemented")

    lines = [
        "# F4.1 机型校准矩阵",
        "",
        "> 生成工具：`python tools/calibration_collect.py matrix`",
        "> 数据源：`vendor/ainative/config/calibration/profiles/*.json`",
        "> 说明：实测列取值 `null` 表示尚未在真机采集（见各档案 measurement.pendingFields）",
        "",
        "## 1. 机型总览",
        "",
        "| 档案 | SoC | 分级 | 分辨率 | 密度 | 刷新率 | 能力位 | T1 | T2 | T3 | 手势 | 状态 |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for path in profiles:
        p = load_json(path)
        ident, disp, s7 = p["identity"], p["display"], p["s7"]
        res = (
            "%sx%s" % (disp.get("widthPx"), disp.get("heightPx"))
            if disp.get("widthPx") and disp.get("heightPx")
            else "待采集"
        )
        gestures = "/".join(
            name for name in ("shake", "flip", "swipe")
            if s7["gestures"].get(name, {}).get("supported")
        ) or "无"

        def mark(enabled):
            return "Y" if enabled else "N"

        lines.append(
            "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                p["profileId"], ident.get("soc"), ident.get("tier"), res,
                disp.get("densityDpi") or "待采集",
                disp.get("refreshRateHz") or "待采集",
                s7.get("motionCapabilitiesHex"),
                mark(s7["tiers"]["foreground"].get("enabled")),
                mark(s7["tiers"]["deepSleep"].get("enabled")),
                mark(s7["tiers"]["sensitiveStop"].get("enabled")),
                gestures,
                p["measurement"].get("status"),
            )
        )

    lines += [
        "",
        "## 2. 宏与降载参数",
        "",
        "| 档案 | 坐标缩放 X/Y | 支持策略 | 等待阈值/上下限 | 捕获降采样 | 注入帧率上限 | 渲染降采样 |",
        "|---|---|---|---|---|---|---|",
    ]
    for path in profiles:
        p = load_json(path)
        macro, timing, render = p["macro"], p["timing"], p["render"]
        lines.append(
            "| %s | %s / %s | %s | %s / %s-%s | %s→%s | %s fps | %s |" % (
                p["profileId"],
                macro.get("coordinateScaleX", "待采集"), macro.get("coordinateScaleY", "待采集"),
                ",".join(str(s) for s in macro.get("supportedStrategies", [])),
                timing.get("waitInsertThresholdMs"), timing.get("waitClampMinMs"),
                timing.get("waitClampMaxMs"),
                render["captureDownsample"].get("defaultFactor"),
                render["captureDownsample"].get("minFactor"),
                render.get("injectionFrameRateCapFps"),
                render["renderDownscale"].get("supported"),
            )
        )

    lines += [
        "",
        "## 3. DMI 可用性基线",
        "",
        "已实现（可被资产引用）：%s" % ("、".join("`%s`" % t for t in implemented) or "无"),
        "",
        "stub（不得被资产引用）：%s" % (
            "、".join(
                "`%s`" % t for t, s in sorted(state_by_tool.items()) if s == "stub"
            ) or "无"
        ),
        "",
        "## 4. 待采集字段汇总",
        "",
        "| 档案 | 待测项数 | 明细 |",
        "|---|---|---|",
    ]
    for path in profiles:
        p = load_json(path)
        pending = p["measurement"].get("pendingFields") or []
        lines.append("| %s | %d | %s |" % (p["profileId"], len(pending), "，".join(pending)))

    content = "\n".join(lines) + "\n"

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(content)
        print("[OK]   已导出校准矩阵: %s" % os.path.relpath(args.out, REPO_ROOT))
    else:
        sys.stdout.write(content)
    return 0


# ---------------------------------------------------------------------------
# regression（F4.2 校准回归样本集）
# ---------------------------------------------------------------------------
REGRESSION_DIRNAME = "regression"
SUITE_SCHEMA_NAME = "suite.schema.json"

REPLAY_SOURCES = {
    "same_device": "macro.successRateTarget.sameDevice",
    "cross_device": "macro.successRateTarget.crossDevice",
    "cross_version": "macro.successRateTarget.crossVersion",
}
TIMING_SOURCES = {
    "commandToVisible": "timing.targets.commandToVisibleP95Ms",
    "injectFallback": "timing.targets.injectFallbackP95Ms",
    "captureToPerception": "timing.targets.captureToPerceptionP95Ms",
}
DOWNSAMPLE_SOURCES = {
    "captureDownsampleFactor": (
        "render.captureDownsample.defaultFactor",
        "render.captureDownsample.minFactor",
    ),
    "injectionFrameRateCapFps": ("render.injectionFrameRateCapFps",),
}
GATE_METRICS = ("aonIsland", "motionTierEnabled", "gestureSupported")
CASE_CATEGORIES = ("coordinate", "replay", "timing", "downsample", "dmi_gate", "s7_gate")


def iter_suites(calibration_dir):
    reg_dir = os.path.join(calibration_dir, REGRESSION_DIRNAME)
    if not os.path.isdir(reg_dir):
        return []
    return sorted(
        os.path.join(reg_dir, name)
        for name in os.listdir(reg_dir)
        if name.endswith(".suite.json")
    )


def deep_get(obj, path):
    cur = obj
    for token in path.split("."):
        if not isinstance(cur, dict) or token not in cur:
            return None, False
        cur = cur[token]
    return cur, True


def _values_equal(expected, actual):
    if isinstance(expected, bool) or isinstance(actual, bool):
        return expected == actual
    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        return abs(float(expected) - float(actual)) <= 1e-9
    return expected == actual


def _check_case(case, profile, registry_by_tool, errors):
    """按类别校验单条用例，并复算黄金值是否与机型档案一致。"""
    case_id = case.get("caseId", "?")
    category = case.get("category")
    params = case.get("params") or {}
    assertion = case.get("assertion") or {}
    metric = assertion.get("metric")
    op = assertion.get("op")
    value = assertion.get("value")
    source = assertion.get("source") or ""

    def fail(msg):
        errors.append("%s: %s" % (case_id, msg))

    # 门控指标：黄金值为 false 表示本机型裁剪，必须显式声明为跳过，否则真机误报失败
    if metric in GATE_METRICS and value is False and case.get("onUnsupported", "fail") != "skip":
        fail("黄金值为 false（本机型裁剪）时 onUnsupported 必须为 skip")

    # 1) dmi_gate：来源为可用性登记表
    if category == "dmi_gate":
        if metric != "dmiBridgeState":
            fail("dmi_gate 用例的 metric 必须为 dmiBridgeState")
        if not source.startswith("dmiAvailability.registryRef#"):
            fail("dmi_gate 用例的 source 必须为 dmiAvailability.registryRef#<toolName>")
            return
        tool = source.split("#", 1)[1]
        if tool != params.get("toolName"):
            fail("params.toolName 与 source 中的工具名不一致")
        if tool not in registry_by_tool:
            fail("桥 %s 未在 dmi-availability.json 登记" % tool)
        elif registry_by_tool[tool] != "implemented":
            fail("桥 %s 状态为 %s，样本集只能断言 implemented" % (tool, registry_by_tool[tool]))
        if value != "implemented":
            fail("dmi_gate 黄金值必须为 implemented，实际 %r" % (value,))
        return

    # 2) coordinate：黄金值为由归一化基数推导的往返容差，不等于 source 字段取值本身
    if category == "coordinate":
        if metric != "roundTripTolerancePx" or op != "le":
            fail("coordinate 用例的 metric/op 必须为 roundTripTolerancePx/le")
        if source != "display.coordinateSpace.normalizedBase":
            fail("coordinate 用例的 source 必须为 display.coordinateSpace.normalizedBase")
        base, found = deep_get(profile, "display.coordinateSpace.normalizedBase")
        if not found:
            fail("档案缺少 display.coordinateSpace.normalizedBase")
        elif params.get("normalizedBase") != base:
            fail("params.normalizedBase 与档案归一化基数不一致")
        if not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
            fail("往返映射容差黄金值必须为正数（像素）")
        return

    # 3) 其余类别：黄金值须可由机型档案字段复算
    if source.startswith("dmiAvailability"):
        fail("非 dmi_gate 用例不得以可用性登记表作为来源")
        return
    profile_value, found = deep_get(profile, source)
    if not found:
        fail("来源字段不存在于机型档案: %s" % source)
        return
    if not _values_equal(value, profile_value):
        fail("黄金值 %r 与档案 %s=%r 不一致（校准漂移，需复核样本集）" % (value, source, profile_value))

    if category == "replay":
        if metric != "successRate" or op != "ge":
            fail("replay 用例的 metric/op 必须为 successRate/ge")
        relation = params.get("deviceRelation")
        if relation not in REPLAY_SOURCES:
            fail("params.deviceRelation 必须为 %s" % "/".join(sorted(REPLAY_SOURCES)))
        elif source != REPLAY_SOURCES[relation]:
            fail("source 应为 %s" % REPLAY_SOURCES[relation])
        if params.get("strategy") not in (profile.get("macro", {}).get("supportedStrategies") or []):
            fail("params.strategy 不在档案 macro.supportedStrategies 中")
    elif category == "timing":
        if metric != "p95LatencyMs" or op != "le":
            fail("timing 用例的 metric/op 必须为 p95LatencyMs/le")
        target = params.get("target")
        if target not in TIMING_SOURCES:
            fail("params.target 必须为 %s" % "/".join(sorted(TIMING_SOURCES)))
        elif source != TIMING_SOURCES[target]:
            fail("source 应为 %s" % TIMING_SOURCES[target])
    elif category == "downsample":
        if metric not in DOWNSAMPLE_SOURCES:
            fail("downsample 用例的 metric 必须为 %s" % "/".join(sorted(DOWNSAMPLE_SOURCES)))
        elif source not in DOWNSAMPLE_SOURCES[metric]:
            fail("source 应为 %s" % " 或 ".join(DOWNSAMPLE_SOURCES[metric]))
        if metric == "captureDownsampleFactor" and op != "eq":
            fail("降采样倍率用例的 op 必须为 eq")
    elif category == "s7_gate":
        if metric not in GATE_METRICS:
            fail("s7_gate 用例的 metric 必须为 %s" % "/".join(GATE_METRICS))
        elif metric == "motionTierEnabled":
            if source != "s7.tiers.%s.enabled" % params.get("tier"):
                fail("motionTierEnabled 用例的 source 应为 s7.tiers.<params.tier>.enabled")
        elif metric == "gestureSupported":
            if source != "s7.gestures.%s.supported" % params.get("gesture"):
                fail("gestureSupported 用例的 source 应为 s7.gestures.<params.gesture>.supported")
        elif source != "s7.sensorHub.aonIsland":
            fail("aonIsland 用例的 source 必须为 s7.sensorHub.aonIsland")
        if not isinstance(value, bool):
            fail("s7_gate 黄金值必须为布尔")


def cmd_regression_validate(args):
    calibration_dir = args.dir or DEFAULT_CALIBRATION_DIR
    reg_dir = os.path.join(calibration_dir, REGRESSION_DIRNAME)
    schema_path = os.path.join(reg_dir, SUITE_SCHEMA_NAME)
    if not os.path.isfile(schema_path):
        print("[FAIL] 找不到样本集 schema: %s" % schema_path)
        return 1
    schema = load_json(schema_path)

    suites = iter_suites(calibration_dir)
    if not suites:
        print("[FAIL] 未找到任何样本集: %s/*.suite.json" % reg_dir)
        return 1

    availability_path = os.path.join(calibration_dir, "dmi-availability.json")
    registry_by_tool = {}
    if os.path.isfile(availability_path):
        registry_by_tool = {
            b.get("toolName"): b.get("state")
            for b in load_json(availability_path).get("bridges", [])
        }

    errors_total = 0
    seen_suite_ids = {}
    run_count = 0
    skip_count = 0

    for path in suites:
        rel = os.path.relpath(path, REPO_ROOT)
        errors = []
        try:
            suite = load_json(path)
        except ValueError as exc:
            print("[FAIL] %s: JSON 解析失败: %s" % (rel, exc))
            errors_total += 1
            continue
        validate_node(suite, schema, schema, "", errors)

        suite_id = suite.get("suiteId")
        if suite_id in seen_suite_ids:
            errors.append("suiteId 重复: %s（已用于 %s）" % (suite_id, seen_suite_ids[suite_id]))
        seen_suite_ids[suite_id] = rel

        profile_ref = suite.get("profileRef")
        profile_path = os.path.normpath(os.path.join(os.path.dirname(path), profile_ref or ""))
        profile = None
        if not profile_ref or not os.path.isfile(profile_path):
            errors.append("profileRef 指向的机型档案不存在: %s" % profile_ref)
        else:
            try:
                profile = load_json(profile_path)
            except ValueError as exc:
                errors.append("机型档案解析失败: %s" % exc)
            else:
                expected_id = "calib_regression_" + str(profile.get("profileId"))
                if suite_id != expected_id:
                    print("[WARN] %s: suiteId 建议为 %s" % (rel, expected_id))

        if profile is not None:
            case_ids = set()
            for case in suite.get("cases", []):
                cid = case.get("caseId")
                if cid in case_ids:
                    errors.append("caseId 重复: %s" % cid)
                case_ids.add(cid)
                _check_case(case, profile, registry_by_tool, errors)
                if (case.get("assertion") or {}).get("value") is False:
                    skip_count += 1
                else:
                    run_count += 1

        if errors:
            print("[FAIL] %s" % rel)
            for err in errors:
                print("        - %s" % err)
            errors_total += len(errors)
        else:
            print("[OK]   %s（%d 用例，profile=%s）" % (
                rel, len(suite.get("cases", [])), suite.get("profileRef")))

    print("[INFO] 真机执行计划：执行 %d 项 / 预期跳过 %d 项（机型能力裁剪）" % (run_count, skip_count))
    return 1 if errors_total else 0


def cmd_regression_matrix(args):
    calibration_dir = args.dir or DEFAULT_CALIBRATION_DIR
    suites = iter_suites(calibration_dir)
    if not suites:
        print("[FAIL] 未找到样本集")
        return 1

    lines = [
        "# F4.2 校准回归样本库",
        "",
        "> 生成工具：`python tools/calibration_collect.py regression-matrix`",
        "> 数据源：`vendor/ainative/config/calibration/regression/*.suite.json`",
        "> 说明：黄金值来源见各用例 `assertion.source`；预期跳过项为机型能力裁剪（如 P1 无 AON 岛）",
        "",
        "## 1. 样本集总览",
        "",
        "| 样本集 | 机型档案 | 分级 | 用例数 | 预期执行 | 预期跳过 | 状态 |",
        "|---|---|---|---|---|---|---|",
    ]
    loaded = []
    for path in suites:
        suite = load_json(path)
        profile_path = os.path.normpath(
            os.path.join(os.path.dirname(path), suite.get("profileRef") or ""))
        profile = load_json(profile_path) if os.path.isfile(profile_path) else {}
        cases = suite.get("cases", [])
        skipped = [c for c in cases if (c.get("assertion") or {}).get("value") is False]
        loaded.append((suite, profile, cases, skipped))
        lines.append("| %s | %s | %s | %d | %d | %d | %s |" % (
            suite.get("suiteId"),
            os.path.basename(profile_path),
            (profile.get("identity") or {}).get("tier", "?"),
            len(cases), len(cases) - len(skipped), len(skipped), suite.get("status")))

    lines += ["", "## 2. 用例分类分布", "",
              "| 样本集 | " + " | ".join(CASE_CATEGORIES) + " |",
              "|---" * (len(CASE_CATEGORIES) + 1) + "|"]
    for suite, _, cases, _ in loaded:
        counts = {c: 0 for c in CASE_CATEGORIES}
        for case in cases:
            if case.get("category") in counts:
                counts[case["category"]] += 1
        lines.append("| %s | %s |" % (
            suite.get("suiteId"), " | ".join(str(counts[c]) for c in CASE_CATEGORIES)))

    lines += ["", "## 3. 机型能力裁剪（预期跳过项）", "",
              "| 样本集 | 用例 | 指标 | 黄金值 | 裁剪原因 |", "|---|---|---|---|---|"]
    for suite, profile, _, skipped in loaded:
        for case in skipped:
            assertion = case.get("assertion") or {}
            source = assertion.get("source", "")
            reason = None
            for candidate in (source.replace(".enabled", ".reason"),
                              source.replace(".supported", ".reason"),
                              "s7.sensorHub.type"):
                value, found = deep_get(profile, candidate)
                if found and value:
                    reason = value
                    break
            lines.append("| %s | %s | %s | %s | %s |" % (
                suite.get("suiteId"), case.get("caseId"), assertion.get("metric"),
                assertion.get("value"), reason if reason else "—"))

    lines += ["", "## 4. 关键阈值与来源", "",
              "| 样本集 | 用例 | 指标 | 断言 | 黄金值 | 来源 |", "|---|---|---|---|---|---|"]
    for suite, _, cases, _ in loaded:
        for case in cases:
            if case.get("category") not in ("coordinate", "replay", "timing", "downsample"):
                continue
            assertion = case.get("assertion") or {}
            lines.append("| %s | %s | %s | %s | %s | `%s` |" % (
                suite.get("suiteId"), case.get("caseId"), assertion.get("metric"),
                assertion.get("op"), assertion.get("value"), assertion.get("source")))

    content = "\n".join(lines) + "\n"
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(content)
        print("[OK]   已导出校准回归样本库: %s" % os.path.relpath(args.out, REPO_ROOT))
    else:
        sys.stdout.write(content)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="F4.1/F4.2 机型校准库工具")
    sub = parser.add_subparsers(dest="command")

    p_validate = sub.add_parser("validate", help="校验机型档案与 DMI 可用性登记表")
    p_validate.add_argument("--dir", help="校准库目录（默认 vendor/ainative/config/calibration）")
    p_validate.add_argument("--schema", help="schema 路径（默认机器档案 schema）")
    p_validate.set_defaults(func=cmd_validate)

    p_collect = sub.add_parser("collect", help="由设备 dump 填充机型档案")
    p_collect.add_argument("--dump", required=True, help="设备 dump JSON")
    p_collect.add_argument("--profile", required=True, help="机型档案 JSON")
    p_collect.add_argument("--samples", help="宏录制样本 JSON（可选，用于时序聚合）")
    p_collect.add_argument("--out", help="输出路径（默认原地更新）")
    p_collect.add_argument("--apply", action="store_true", help="用设备能力位覆盖档案值")
    p_collect.set_defaults(func=cmd_collect)

    p_matrix = sub.add_parser("matrix", help="导出机型校准矩阵（Markdown）")
    p_matrix.add_argument("--dir", help="校准库目录")
    p_matrix.add_argument("--out", help="输出文件（默认打印到 stdout）")
    p_matrix.set_defaults(func=cmd_matrix)

    p_reg_validate = sub.add_parser("regression-validate", help="校验校准回归样本集（F4.2）")
    p_reg_validate.add_argument("--dir", help="校准库目录（默认 vendor/ainative/config/calibration）")
    p_reg_validate.set_defaults(func=cmd_regression_validate)

    p_reg_matrix = sub.add_parser("regression-matrix", help="导出校准回归样本库（Markdown）")
    p_reg_matrix.add_argument("--dir", help="校准库目录")
    p_reg_matrix.add_argument("--out", help="输出文件（默认打印到 stdout）")
    p_reg_matrix.set_defaults(func=cmd_regression_matrix)

    args = parser.parse_args(argv)
    if not getattr(args, "func", None):
        parser.print_help()
        return 2
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
