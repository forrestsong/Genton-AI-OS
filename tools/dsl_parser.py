#!/usr/bin/env python3
"""
AI Native OS - 策略 DSL 解析器（纯标准库）

任务编号：H1.1 / H2.1（质量工程部）
验收项  ：DSL C1-C8 编译检查的解析基础；策略引擎单测的规则装载基础

解析 vendor/ainative/config/policy_baseline.dsl 的自定义 DSL 语法
（rule <id> { ... } 块结构），输出与 policy_dsl_compiler.py 兼容的
规则字典列表：

    {
        "id": "sys.action.baseline.payment-confirm",
        "baseline": true,
        "domain": "action",
        "subject": "any",
        "when": {"capability": "agent.payment_action"},
        "effect": "require_confirmation",
        "priority": 1000,
        "audit": "mandatory",
        "_meta": {"version": "1.0.0", "immutable": true, ...}
    }

支持的 DSL 值语法：
- 块：  key: { ... }   （递归嵌套）
- 列表：key: [ a b c ] （允许无逗号分隔的元素，兼容 .dsl 基线文件写法）
- 字符串："double quoted" 或单引号
- 裸字：true/false/null/any/mandatory 等（按类型推断）
- 数字：整数/浮点
- 特殊值：starts_with("x") / matches("x") / contains("a","b") / missing()
  解析为字符串原文（如 'starts_with("query.")'），由求值器解释

依赖：仅 Python 3 标准库
运行：python3 tools/dsl_parser.py <policy.dsl>   （输出 JSON 规则列表）
"""

import json
import re
import sys
from typing import Any, Dict, List, Optional, Tuple

# Windows 控制台 UTF-8 输出兼容
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - 非 tty 环境
    pass


class DslParseError(Exception):
    """DSL 解析错误"""


# ============================================================
# 词法扫描器
# ============================================================

_TOKEN_RE = re.compile(r"""
    (?P<space>\s+)
  | (?P<comment>\#[^\n]*)
  | (?P<lbrace>\{)
  | (?P<rbrace>\})
  | (?P<lbrack>\[)
  | (?P<rbrack>\])
  | (?P<colon>:)
  | (?P<comma>,)
  | (?P<string>"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')
  | (?P<func>[a-zA-Z_][a-zA-Z0-9_]*\s*\()
  | (?P<lparen>\()
  | (?P<rparen>\))
  | (?P<word>[^\s\{\}\[\]:,"'()]+)
""", re.VERBOSE)


class Token:
    __slots__ = ("kind", "value", "pos")

    def __init__(self, kind: str, value: str, pos: int):
        self.kind = kind
        self.value = value
        self.pos = pos

    def __repr__(self) -> str:  # pragma: no cover
        return f"Token({self.kind}, {self.value!r})"


def tokenize(text: str) -> List[Token]:
    """将 DSL 文本切分为 token 流"""
    tokens: List[Token] = []
    pos = 0
    while pos < len(text):
        m = _TOKEN_RE.match(text, pos)
        if not m:
            raise DslParseError(f"无法解析位置 {pos}: {text[pos:pos+30]!r}")
        kind = m.lastgroup
        value = m.group()
        if kind not in ("space", "comment"):
            if kind == "string":
                # 保留引号内原始内容（含转义），由解析层解码
                tokens.append(Token("string", value, pos))
            elif kind == "func":
                tokens.append(Token("func", value, pos))
            else:
                tokens.append(Token(kind, value, pos))
        pos = m.end()
    return tokens


def _decode_string(raw: str) -> str:
    """解码带引号的字符串字面量"""
    quote = raw[0]
    body = raw[1:-1]
    out: List[str] = []
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            if nxt == "n":
                out.append("\n")
            elif nxt == "t":
                out.append("\t")
            elif nxt == "r":
                out.append("\r")
            elif nxt == quote or nxt == "\\":
                out.append(nxt)
            else:
                out.append(nxt)
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


# ============================================================
# 递归下降解析器
# ============================================================

class Parser:
    def __init__(self, tokens: List[Token]):
        self.tokens = tokens
        self.pos = 0

    def peek(self) -> Optional[Token]:
        return self.tokens[self.pos] if self.pos < len(self.tokens) else None

    def next(self) -> Optional[Token]:
        tok = self.peek()
        if tok is not None:
            self.pos += 1
        return tok

    def expect(self, kind: str) -> Token:
        tok = self.next()
        if tok is None or tok.kind != kind:
            raise DslParseError(f"期望 {kind}，得到 {tok}")
        return tok

    # --------------------------------------------------------
    # 顶层：rule 列表
    # --------------------------------------------------------

    def parse_rules(self) -> List[Dict[str, Any]]:
        rules: List[Dict[str, Any]] = []
        meta_fields: Dict[str, Any] = {}
        while self.peek() is not None:
            tok = self.next()
            if tok.kind != "word":
                raise DslParseError(f"期望 'rule' 关键字或顶层元数据，得到 {tok}")
            if tok.value == "rule":
                id_tok = self.next()
                if id_tok is None or id_tok.kind != "word":
                    raise DslParseError(f"规则缺少 ID: {id_tok}")
                rule_id = id_tok.value
                self.expect("lbrace")
                body = self.parse_block_body()
                body["id"] = rule_id
                rules.append(body)
            else:
                # 顶层元数据块（如 _policyPackMeta），跳过但记录
                key = tok.value
                self.expect("colon")
                meta_fields[key] = self.parse_value()
        return rules

    # --------------------------------------------------------
    # 块体：key: value 的集合（遇到 rbrace 结束）
    # --------------------------------------------------------

    def parse_block_body(self) -> Dict[str, Any]:
        fields: Dict[str, Any] = {}
        while True:
            tok = self.peek()
            if tok is None:
                raise DslParseError("块未闭合（缺少 }）")
            if tok.kind == "rbrace":
                self.next()
                return fields
            if tok.kind == "word":
                self.next()
                key = tok.value
                self.expect("colon")
                fields[key] = self.parse_value()
            else:
                raise DslParseError(f"块内期望字段名，得到 {tok}")

    # --------------------------------------------------------
    # 值解析
    # --------------------------------------------------------

    def parse_value(self) -> Any:
        tok = self.peek()
        if tok is None:
            raise DslParseError("值缺失")
        if tok.kind == "lbrace":
            self.next()
            return self.parse_block_body()
        if tok.kind == "lbrack":
            self.next()
            return self.parse_list_body()
        if tok.kind == "string":
            self.next()
            return _decode_string(tok.value)
        if tok.kind == "func":
            self.next()
            return self.parse_func_call(tok.value)
        if tok.kind == "word":
            self.next()
            return self._infer_word(tok.value)
        raise DslParseError(f"无法解析的值: {tok}")

    def parse_list_body(self) -> List[Any]:
        """列表元素允许以空白或逗号分隔（兼容 .dsl 无逗号写法）"""
        items: List[Any] = []
        while True:
            tok = self.peek()
            if tok is None:
                raise DslParseError("列表未闭合（缺少 ]）")
            if tok.kind == "rbrack":
                self.next()
                return items
            if tok.kind == "comma":
                self.next()
                continue
            items.append(self.parse_value())

    def parse_func_call(self, head: str) -> str:
        """解析函数调用形式的特殊值，返回原始字符串（含调用）"""
        raw = head
        depth = 1
        while depth > 0:
            tok = self.next()
            if tok is None:
                raise DslParseError(f"函数调用未闭合: {head}")
            if tok.kind == "lparen":  # pragma: no cover - func token 已含 '('
                depth += 1
            elif tok.kind == "rparen":
                depth -= 1
            raw += tok.value
        return raw.strip()

    @staticmethod
    def _infer_word(word: str) -> Any:
        """裸字类型推断"""
        if word == "true":
            return True
        if word == "false":
            return False
        if word == "null":
            return None
        # 数字
        try:
            if re.match(r'^-?\d+$', word):
                return int(word)
            if re.match(r'^-?\d+\.\d+$', word):
                return float(word)
        except ValueError:
            pass
        return word


# ============================================================
# 顶层 API
# ============================================================

def parse_dsl_text(text: str) -> List[Dict[str, Any]]:
    """解析 DSL 文本，返回规则字典列表"""
    tokens = tokenize(text)
    parser = Parser(tokens)
    return parser.parse_rules()


def parse_dsl_file(path: str) -> List[Dict[str, Any]]:
    """解析 DSL 文件"""
    with open(path, "r", encoding="utf-8") as f:
        return parse_dsl_text(f.read())


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: python3 tools/dsl_parser.py <policy.dsl> [--json]")
        return 1
    try:
        rules = parse_dsl_file(sys.argv[1])
        if "--json" in sys.argv:
            print(json.dumps(rules, ensure_ascii=False, indent=2))
        else:
            print(f"✓ 解析成功: {len(rules)} 条规则")
            for r in rules:
                print(f"  - {r.get('id')}  domain={r.get('domain')}  "
                      f"effect={r.get('effect')}  priority={r.get('priority')}")
        return 0
    except (DslParseError, FileNotFoundError, OSError) as e:
        print(f"✗ 解析失败: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
