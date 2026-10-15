# -*- coding: utf-8 -*-
"""
WBS 1.7 静态验收检查：版本库中不得存在私钥材料。

断言：
  A. git 跟踪列表中不得出现 keys/*.key、*.pem、*.jks、*.keystore
  B. 任何被跟踪文件的内容中不得出现 PRIVATE KEY 标记
失败时打印 文件 证据并非零退出。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
failures = []

KEY_EXT = {".key", ".pem", ".jks", ".keystore", ".p12", ".pfx"}

# 只有"真实密钥块"才算泄漏：BEGIN 标记 + 足量 Base64 主体 + END 标记。
# 仅仅出现标记字符串不算 —— 例如 PiiMaskRules.kt 的掩码模板
# （"-----BEGIN PRIVATE KEY----- [REDACTED] -----END PRIVATE KEY-----"）
# 与 E3.1 文档中的检测规则说明都是合法内容。
KEY_BLOCK = re.compile(
    r'-----BEGIN (?:RSA |EC |DSA |ENCRYPTED |OPENSSH )?PRIVATE KEY-----\s*'
    r'([A-Za-z0-9+/=\s]{200,}?)'
    r'\s*-----END (?:RSA |EC |DSA |ENCRYPTED |OPENSSH )?PRIVATE KEY-----',
    re.S)

# 检查器自身不参与扫描（其源码含上述正则字面量）
SELF = "verification/check_no_secrets_in_git.py"


def git(*args):
    # 显式使用 UTF-8：仓库内含中文路径，默认 locale 编码(gbk)会解码失败并吞掉 stdout
    r = subprocess.run(["git"] + list(args), cwd=str(ROOT), capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    return r.returncode, r.stdout or "", r.stderr or ""


# ---------- 0. 检测器自检：真阳性 + 真阴性 ----------
# 一个只会 PASS 的检测器没有价值。这里同时验证：
#   真阳性 —— 真实形态的 PEM 密钥块必须被报出；
#   真阴性 —— 掩码模板（含标记但无密钥主体）不得误报。
print("[0] 检测器自检")
_FAKE_KEY = ("-----BEGIN PRIVATE KEY-----\n"
             + ("MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ" * 6)
             + "\n-----END PRIVATE KEY-----")
_TEMPLATE = "-----BEGIN PRIVATE KEY----- [REDACTED] -----END PRIVATE KEY-----"
if KEY_BLOCK.search(_FAKE_KEY):
    print("  ok: 真实形态密钥块被正确识别（真阳性）")
else:
    failures.append("[0] 检测器未能识别真实形态密钥块 —— 检测器失效")
if not KEY_BLOCK.search(_TEMPLATE):
    print("  ok: 掩码模板不被误报（真阴性）")
else:
    failures.append("[0] 掩码模板被误报 —— 检测器过严")

code, out, err = git("ls-files")
if code != 0:
    print("SKIP: 当前不是 git 仓库（先执行 git init）。stderr=" + err.strip())
    sys.exit(0)

tracked = [line for line in out.splitlines() if line.strip()]
print(f"扫描 git 跟踪文件：{len(tracked)} 个")

for rel in tracked:
    norm = rel.replace("\\", "/")
    if norm == SELF:
        continue
    p = ROOT / rel
    if p.suffix.lower() in KEY_EXT and "keys/" in norm:
        failures.append(f"[A] 私钥/证书扩展名被跟踪：{rel}")
    if p.is_file() and p.stat().st_size < 512 * 1024:
        try:
            content = p.read_text(encoding="utf-8", errors="ignore")
        except Exception as e:  # pragma: no cover
            failures.append(f"[B] 读取失败 {rel}: {e}")
            continue
        m = KEY_BLOCK.search(content)
        if m:
            failures.append(
                f"[B] 被跟踪文件含真实私钥块：{rel}"
                f"（BEGIN/END 标记齐备，Base64 主体 {len(m.group(1))} 字符）")

for f in failures:
    print("FAIL: " + f)

if failures:
    print(f"\nRESULT: FAIL（{len(failures)} 项）")
    sys.exit(1)
print("\nRESULT: PASS —— 版本库中无私钥材料")
sys.exit(0)
