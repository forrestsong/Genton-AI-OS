#!/usr/bin/env python3
"""
策略引擎服务端 (Policy Engine Service)
实现 D10 IPolicyEngine 接口规范

职责：
- 加载策略规则集（C1.1 规则目录 + C1.3 基线规则）
- 两阶段求值引擎（when 条件匹配 + then 决策）
- 冲突裁决（实现 C1.3 冲突矩阵逻辑）
- 令牌签发与验证
- 审计集成
"""

import yaml
import hmac
import hashlib
import time
import re
from typing import Dict, List, Any, Optional, Set, Tuple
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path


# ============================================================
# 常量定义（对应 D10 IPolicyEngine.aidl）
# ============================================================

class Status(Enum):
    """策略引擎状态码"""
    OK = 0
    DENIED = 1
    UNKNOWN_CAP = 2
    INVALID_REQ = 3
    TOKEN_INVALID = 4
    CONFIRM_PENDING = 5
    INTERNAL_ERROR = -1


class Decision(Enum):
    """裁决决策"""
    GRANT = 0
    DENY = 1
    REQUIRE_CONFIRMATION = 2
    REQUIRE_MITIGATION = 3


class UserDecision(Enum):
    """用户确认决策"""
    ALLOW_ONCE = 0
    ALLOW_TASK = 1
    DENY = 2


class Domain(Enum):
    """策略域"""
    ACTION = "action"
    NOTIFICATION = "notification"
    CALL_GATE = "call_gate"


# ============================================================
# 数据结构
# ============================================================

@dataclass
class PolicyRule:
    """策略规则"""
    id: str
    domain: Domain
    effect: str
    when: Dict[str, Any]
    then: Dict[str, Any]
    priority: int
    baseline: bool = False
    subject: Optional[Dict[str, Any]] = None
    meta: Optional[Dict[str, Any]] = None
    source: str = "unknown"  # platform/user/learn
    
    # 统计信息
    hit_count: int = 0
    last_hit_at: float = 0.0


@dataclass
class CapabilityToken:
    """能力令牌"""
    token_id: str
    agent_id: str
    capability: str
    scope_intent_id: str
    expires_at: float
    issuer_signature: str
    revoked: bool = False


@dataclass
class PolicyDecision:
    """策略裁决结果"""
    decision: Decision
    reasons: List[str]
    confirm_id: Optional[str] = None
    token: Optional[CapabilityToken] = None
    required_mitigations: List[str] = field(default_factory=list)
    rule_version: str = "1.0.0"
    matched_rule_id: Optional[str] = None


@dataclass
class EvaluationContext:
    """求值上下文"""
    # 基本信息
    agent_id: str
    intent_id: Optional[str] = None
    
    # 动作信息
    capability: Optional[str] = None
    action: Optional[str] = None
    
    # 环境上下文
    event: Optional[str] = None
    context: Dict[str, Any] = field(default_factory=dict)
    
    # Taint 信息
    arg_taints: Dict[str, str] = field(default_factory=dict)
    
    # 其他域特定字段
    domain_fields: Dict[str, Any] = field(default_factory=dict)


# ============================================================
# 策略引擎核心
# ============================================================

class PolicyEngine:
    """策略引擎服务"""
    
    def __init__(self, config_dir: str = "vendor/ainative/config"):
        self.config_dir = Path(config_dir)
        self.rules: List[PolicyRule] = []
        self.baseline_rules: List[PolicyRule] = []
        self.user_rules: List[PolicyRule] = []
        
        # 令牌管理
        self.tokens: Dict[str, CapabilityToken] = {}
        self.revoked_tokens: Set[str] = set()
        
        # 确认挂起
        self.pending_confirmations: Dict[str, EvaluationContext] = {}
        
        # 能力注册表（简化版，实际应从 A3.1 动作注册表获取）
        self.registered_capabilities: Set[str] = set()
        
        # 密钥（简化版，实际应从 TEE 获取）
        self.signing_key = b"platform-private-key-placeholder"
        
        # 版本
        self.policy_version = "1.0.0"
        
        # 统计
        self.stats = {
            "total_evaluations": 0,
            "total_denies": 0,
            "total_grants": 0,
            "total_confirmations": 0,
            "pending_confirmations": 0
        }
    
    # --------------------------------------------------------
    # 规则加载
    # --------------------------------------------------------
    
    def load_policy_rules(self) -> bool:
        """
        加载策略规则集
        - C1.1 规则目录（notification + call_gate）
        - C1.3 基线规则（action 域）
        """
        try:
            # 1. 加载 C1.1 规则目录
            catalog_path = self.config_dir / "policy_catalog_v1.yaml"
            if catalog_path.exists():
                self._load_catalog_rules(catalog_path)
            
            # 2. 加载 C1.3 基线规则
            baseline_path = self.config_dir / "baseline_policy_rules_v1.yaml"
            if baseline_path.exists():
                self._load_baseline_rules(baseline_path)
            
            # 3. 编译规则集（按 priority 降序排序）
            self._compile_rules()
            
            print(f"✓ 策略引擎加载完成: {len(self.rules)} 条规则")
            print(f"  - baseline 规则: {len(self.baseline_rules)} 条")
            print(f"  - user 规则: {len(self.user_rules)} 条")
            
            return True
            
        except Exception as e:
            print(f"✗ 策略规则加载失败: {e}")
            return False
    
    def _load_catalog_rules(self, path: Path):
        """加载 C1.1 规则目录"""
        with open(path, 'r', encoding='utf-8') as f:
            content = f.read()
            
        # 解析 YAML（多文档格式）
        rules_data = list(yaml.safe_load_all(content))
        
        for rule_dict in rules_data:
            if not rule_dict or not isinstance(rule_dict, dict):
                continue
            
            rule = self._parse_rule(rule_dict)
            if rule:
                self.rules.append(rule)
                
                if rule.baseline:
                    self.baseline_rules.append(rule)
                elif rule.source == "user":
                    self.user_rules.append(rule)
    
    def _load_baseline_rules(self, path: Path):
        """加载 C1.3 基线规则"""
        with open(path, 'r', encoding='utf-8') as f:
            data = yaml.safe_load(f)
        
        # 加载授权轴基线规则
        if 'action_baseline_rules' in data:
            action_rules = data['action_baseline_rules'].get('rules', [])
            for rule_dict in action_rules:
                rule = self._parse_rule(rule_dict)
                if rule:
                    self.rules.append(rule)
                    self.baseline_rules.append(rule)
    
    def _parse_rule(self, rule_dict: Dict[str, Any]) -> Optional[PolicyRule]:
        """解析规则字典为 PolicyRule 对象"""
        try:
            # 必需字段
            rule_id = rule_dict.get('id')
            if not rule_id:
                return None
            
            # 域
            domain_str = rule_dict.get('domain', 'action')
            domain = Domain(domain_str)
            
            # 来源
            if rule_id.startswith('sys.'):
                source = 'platform'
            elif rule_id.startswith('user.'):
                source = 'user'
            elif rule_id.startswith('learn.'):
                source = 'learn'
            else:
                source = 'unknown'
            
            rule = PolicyRule(
                id=rule_id,
                domain=domain,
                effect=rule_dict.get('effect', ''),
                when=rule_dict.get('when', {}),
                then=rule_dict.get('then', {}),
                priority=rule_dict.get('priority', 0),
                baseline=rule_dict.get('baseline', False),
                subject=rule_dict.get('subject'),
                meta=rule_dict.get('_meta'),
                source=source
            )
            
            return rule
            
        except Exception as e:
            print(f"警告: 解析规则失败 {rule_dict.get('id', 'unknown')}: {e}")
            return None
    
    def _compile_rules(self):
        """编译规则集（按优先级排序）"""
        # 按 priority 降序排序（高优先级先评估）
        self.rules.sort(key=lambda r: r.priority, reverse=True)
        
        # baseline 规则必须在最高优先级
        self.baseline_rules.sort(key=lambda r: r.priority, reverse=True)
    
    # --------------------------------------------------------
    # 两阶段求值引擎
    # --------------------------------------------------------
    
    def evaluate_intent(self, context: EvaluationContext) -> PolicyDecision:
        """
        意图级预检（D10 evaluateIntent）
        用于规划前快速判断意图是否可行
        """
        self.stats["total_evaluations"] += 1
        
        # 简化版：意图级只做粗粒度检查
        # 实际应检查 requestedCapabilities 是否全部可用
        
        decision = PolicyDecision(
            decision=Decision.GRANT,
            reasons=["intent_preliminary_check_passed"],
            rule_version=self.policy_version
        )
        
        return decision
    
    def request_capability(self, context: EvaluationContext) -> PolicyDecision:
        """
        动作级令牌请求（D10 requestCapability）
        
        实现两阶段求值：
        1. when 条件匹配
        2. then 决策生成
        """
        self.stats["total_evaluations"] += 1
        
        # 1. 能力注册表检查
        if context.capability and context.capability not in self.registered_capabilities:
            # 临时：允许所有能力（实际应严格检查）
            pass
        
        # 2. 匹配规则
        matched_rules = self._match_rules(context)
        
        # 3. 冲突裁决
        decision = self._resolve_conflicts(matched_rules, context)
        
        # 4. 生成令牌（如果 GRANT）
        if decision.decision == Decision.GRANT:
            token = self._issue_token(context)
            decision.token = token
            self.stats["total_grants"] += 1
        elif decision.decision == Decision.DENY:
            self.stats["total_denies"] += 1
        elif decision.decision == Decision.REQUIRE_CONFIRMATION:
            # 生成 confirmId 并挂起
            confirm_id = self._generate_confirm_id(context)
            decision.confirm_id = confirm_id
            self.pending_confirmations[confirm_id] = context
            self.stats["total_confirmations"] += 1
            self.stats["pending_confirmations"] += 1
        
        return decision
    
    def _match_rules(self, context: EvaluationContext) -> List[PolicyRule]:
        """
        阶段 1：when 条件匹配
        返回所有匹配的规则
        """
        matched = []
        
        for rule in self.rules:
            # 跳过 learn 规则（不参与裁决）
            if rule.source == 'learn':
                continue
            
            # 域匹配
            if rule.domain != context.domain_fields.get('domain', Domain.ACTION):
                # 如果上下文没有指定 domain，根据规则域判断
                if context.capability and rule.domain == Domain.ACTION:
                    pass
                else:
                    continue
            
            # when 条件匹配
            if self._evaluate_when_condition(rule.when, context):
                matched.append(rule)
                
                # 更新统计
                rule.hit_count += 1
                rule.last_hit_at = time.time()
        
        return matched
    
    def _evaluate_when_condition(self, when: Dict[str, Any], context: EvaluationContext) -> bool:
        """
        评估 when 条件是否匹配
        
        支持的条件类型：
        - 精确匹配：capability: "agent.send_message"
        - 列表匹配：category: [alarm, system_critical]
        - 比较运算：classifierConfidence: ">=0.85"
        - 嵌套字段：context.dndActive: true
        """
        for key, expected_value in when.items():
            # 获取上下文中的实际值
            actual_value = self._get_context_value(key, context)
            
            # 匹配逻辑
            if not self._match_value(actual_value, expected_value):
                return False
        
        return True
    
    def _get_context_value(self, key: str, context: EvaluationContext) -> Any:
        """从上下文获取字段值"""
        # 嵌套字段（如 context.dndActive）
        if '.' in key:
            parts = key.split('.')
            if parts[0] == 'context':
                return context.context.get(parts[1])
        
        # 直接字段
        if key == 'event':
            return context.event
        elif key == 'capability':
            return context.capability
        elif key == 'action':
            return context.action
        elif key in context.domain_fields:
            return context.domain_fields[key]
        
        return None
    
    def _match_value(self, actual: Any, expected: Any) -> bool:
        """值匹配逻辑"""
        if expected is None:
            return actual is None
        
        # 列表匹配（actual in expected）
        if isinstance(expected, list):
            return actual in expected
        
        # 字符串比较运算符（如 ">=0.85"）
        if isinstance(expected, str) and isinstance(actual, (int, float)):
            return self._compare_numeric(actual, expected)
        
        # 精确匹配
        return actual == expected
    
    def _compare_numeric(self, value: float, expr: str) -> bool:
        """比较数值表达式"""
        # 支持 >=, <=, >, <, ==
        if expr.startswith('>='):
            return value >= float(expr[2:])
        elif expr.startswith('<='):
            return value <= float(expr[2:])
        elif expr.startswith('>'):
            return value > float(expr[1:])
        elif expr.startswith('<'):
            return value < float(expr[1:])
        elif expr.startswith('=='):
            return value == float(expr[2:])
        else:
            return False
    
    def _resolve_conflicts(self, matched_rules: List[PolicyRule], context: EvaluationContext) -> PolicyDecision:
        """
        阶段 2：冲突裁决
        
        实现 C1.3 冲突矩阵逻辑：
        1. baseline deny 永远优先
        2. 同 priority：deny > require_confirmation > allow
        3. 不同 priority：高优先级获胜
        4. 无匹配规则：action 域 DENY，notification/call_gate 域 GRANT（原生路由）
        """
        # 无匹配规则
        if not matched_rules:
            return self._default_decision(context)
        
        # 分离 baseline deny 规则
        baseline_deny_rules = [
            r for r in matched_rules 
            if r.baseline and r.effect == 'deny'
        ]
        
        # baseline deny 永远优先（C1.3 冲突矩阵样例 CM-001/CM-002）
        if baseline_deny_rules:
            rule = baseline_deny_rules[0]  # priority 相同时取第一个
            return PolicyDecision(
                decision=Decision.DENY,
                reasons=[f"baseline_deny:{rule.id}", rule.then.get('reason', '')],
                matched_rule_id=rule.id,
                rule_version=self.policy_version
            )
        
        # 按 priority 降序（已排序），同 priority 按 effect 优先级
        # deny > require_confirmation > allow/route_*
        for rule in matched_rules:
            effect = rule.effect
            
            # deny（包括 baseline 的非 deny 规则）
            if effect == 'deny':
                return PolicyDecision(
                    decision=Decision.DENY,
                    reasons=[f"rule:{rule.id}", rule.then.get('reason', '')],
                    matched_rule_id=rule.id,
                    rule_version=self.policy_version
                )
        
        # require_confirmation
        for rule in matched_rules:
            if rule.effect == 'require_confirmation':
                return PolicyDecision(
                    decision=Decision.REQUIRE_CONFIRMATION,
                    reasons=[f"rule:{rule.id}", rule.then.get('reason', '')],
                    matched_rule_id=rule.id,
                    rule_version=self.policy_version
                )
        
        # allow / route_*
        for rule in matched_rules:
            if rule.effect in ['allow', 'route_p0_native', 'route_p1_digest', 
                               'route_p2_agent_act', 'route_ring_native', 
                               'route_agent_handle', 'route_record_only']:
                return PolicyDecision(
                    decision=Decision.GRANT,
                    reasons=[f"rule:{rule.id}", rule.then.get('route', rule.effect)],
                    matched_rule_id=rule.id,
                    rule_version=self.policy_version
                )
        
        # 兜底：无匹配
        return self._default_decision(context)
    
    def _default_decision(self, context: EvaluationContext) -> PolicyDecision:
        """
        默认裁决（无匹配规则时）
        
        C1.3 冲突矩阵样例 CM-013/CM-014/CM-015：
        - action 域：DENY（最小权限原则）
        - notification 域：GRANT（P0 原生路由）
        - call_gate 域：GRANT（RING_NATIVE）
        """
        domain = context.domain_fields.get('domain', Domain.ACTION)
        
        if domain == Domain.ACTION:
            return PolicyDecision(
                decision=Decision.DENY,
                reasons=["no_matching_rule", "default_deny"],
                rule_version=self.policy_version
            )
        elif domain == Domain.NOTIFICATION:
            return PolicyDecision(
                decision=Decision.GRANT,
                reasons=["no_matching_rule", "default_p0_native"],
                rule_version=self.policy_version
            )
        elif domain == Domain.CALL_GATE:
            return PolicyDecision(
                decision=Decision.GRANT,
                reasons=["no_matching_rule", "default_ring_native"],
                rule_version=self.policy_version
            )
        else:
            return PolicyDecision(
                decision=Decision.DENY,
                reasons=["no_matching_rule", "unknown_domain"],
                rule_version=self.policy_version
            )
    
    # --------------------------------------------------------
    # 令牌管理
    # --------------------------------------------------------
    
    def _issue_token(self, context: EvaluationContext) -> CapabilityToken:
        """
        签发能力令牌
        
        令牌包含：
        - token_id: 唯一标识
        - agent_id: Agent 标识
        - capability: 能力名
        - scope_intent_id: 意图范围
        - expires_at: 过期时间（短时）
        - issuer_signature: 平台签名
        """
        token_id = self._generate_token_id(context)
        
        # 短时令牌（5 分钟）
        expires_at = time.time() + 300
        
        # 生成签名
        signature = self._sign_token(token_id, context.agent_id, 
                                     context.capability, context.intent_id)
        
        token = CapabilityToken(
            token_id=token_id,
            agent_id=context.agent_id,
            capability=context.capability,
            scope_intent_id=context.intent_id,
            expires_at=expires_at,
            issuer_signature=signature
        )
        
        # 存储
        self.tokens[token_id] = token
        
        return token
    
    def _generate_token_id(self, context: EvaluationContext) -> str:
        """生成令牌 ID"""
        import uuid
        return f"tk-{uuid.uuid4().hex[:16]}"
    
    def _generate_confirm_id(self, context: EvaluationContext) -> str:
        """生成确认 ID"""
        import uuid
        return f"cf-{uuid.uuid4().hex[:16]}"
    
    def _sign_token(self, token_id: str, agent_id: str, 
                   capability: str, intent_id: str) -> str:
        """签名令牌（HMAC-SHA256）"""
        message = f"{token_id}:{agent_id}:{capability}:{intent_id}"
        signature = hmac.new(
            self.signing_key,
            message.encode('utf-8'),
            hashlib.sha256
        ).hexdigest()
        return signature
    
    def validate_token(self, token_id: str, scope_intent_id: str, 
                      capability: str) -> bool:
        """
        令牌三查（D10 validateToken）
        
        检查：
        1. 签名有效
        2. scope 匹配
        3. 未过期/未撤销
        """
        token = self.tokens.get(token_id)
        if not token:
            return False
        
        # 1. 撤销检查
        if token.revoked or token_id in self.revoked_tokens:
            return False
        
        # 2. 过期检查
        if time.time() > token.expires_at:
            return False
        
        # 3. scope 检查
        if token.scope_intent_id != scope_intent_id:
            return False
        
        # 4. capability 检查
        if token.capability != capability:
            return False
        
        # 5. 签名检查
        expected_signature = self._sign_token(
            token_id, token.agent_id, capability, scope_intent_id
        )
        if token.issuer_signature != expected_signature:
            return False
        
        return True
    
    def revoke_tokens(self, agent_id: str, intent_id: Optional[str] = None) -> int:
        """
        撤销令牌（D10 revokeTokens）
        
        返回撤销数量
        """
        count = 0
        for token_id, token in self.tokens.items():
            if token.agent_id == agent_id:
                if intent_id is None or token.scope_intent_id == intent_id:
                    token.revoked = True
                    self.revoked_tokens.add(token_id)
                    count += 1
        
        return count
    
    def report_user_decision(self, confirm_id: str, user_decision: UserDecision) -> Optional[PolicyDecision]:
        """
        用户决策回灌（D10 reportUserDecision）
        
        返回更新后的决策（如 ALLOW 则签发令牌）
        """
        context = self.pending_confirmations.get(confirm_id)
        if not context:
            return None
        
        # 移除挂起
        del self.pending_confirmations[confirm_id]
        self.stats["pending_confirmations"] -= 1
        
        if user_decision == UserDecision.DENY:
            return PolicyDecision(
                decision=Decision.DENY,
                reasons=["user_denied"],
                rule_version=self.policy_version
            )
        else:
            # ALLOW_ONCE / ALLOW_TASK
            token = self._issue_token(context)
            return PolicyDecision(
                decision=Decision.GRANT,
                reasons=["user_approved", f"scope:{user_decision.name}"],
                token=token,
                rule_version=self.policy_version
            )
    
    # --------------------------------------------------------
    # 统计与治理
    # --------------------------------------------------------
    
    def get_stats(self) -> Dict[str, Any]:
        """获取统计信息"""
        return {
            **self.stats,
            "total_rules": len(self.rules),
            "baseline_rules": len(self.baseline_rules),
            "user_rules": len(self.user_rules),
            "active_tokens": len([t for t in self.tokens.values() if not t.revoked]),
            "policy_version": self.policy_version
        }
    
    def list_applicable_rules(self, agent_id: str) -> List[Dict[str, Any]]:
        """列出适用于特定 Agent 的规则（治理视图）"""
        applicable = []
        
        for rule in self.rules:
            # 简化版：返回所有规则
            # 实际应根据 subject 过滤
            applicable.append({
                "rule_id": rule.id,
                "baseline": rule.baseline,
                "effect": rule.effect,
                "priority": rule.priority,
                "hit_count": rule.hit_count,
                "last_hit_at": rule.last_hit_at
            })
        
        return applicable


# ============================================================
# 辅助函数
# ============================================================

def create_policy_engine(config_dir: str = "vendor/ainative/config") -> Optional[PolicyEngine]:
    """创建并初始化策略引擎"""
    engine = PolicyEngine(config_dir)
    
    # 加载规则
    if not engine.load_policy_rules():
        return None
    
    return engine
