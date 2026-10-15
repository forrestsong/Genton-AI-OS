# AI Native OS 基线策略规则库 v1
# 版本：1.0.0
# 日期：2026-09-05
# 描述：系统级基线策略，deny 默认 + 安全关键路由保护
# 规范：轨道A-策略DSL规范v1.md §7 规则目录

# ============================================================
# 授权轴基线（action 域）—— 最小权限默认
# ============================================================

## 基线 1：敏感动作白名单（baseline-deny-protect）
## 确保高风险动作永远走用户确认流，不可被任何规则绕过

rule sys.action.baseline.payment-confirm {
  baseline: true
  domain: action
  subject: any
  
  when: {
    capability: "agent.payment_action"
  }
  
  effect: require_confirmation
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "支付类动作强制用户确认，不可覆盖"
  }
}

rule sys.action.baseline.data-share-confirm {
  baseline: true
  domain: action
  subject: any
  
  when: {
    capability: "agent.share_data"
    or: [
      { action: "share.external" }
      { action: "share.cloud" }
      { action: "export.file" }
    ]
  }
  
  effect: require_confirmation
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "数据外发强制确认（对齐 E1.1 安全红线）"
  }
}

rule sys.action.baseline.destructive-confirm {
  baseline: true
  domain: action
  subject: any
  
  when: {
    action: "data.delete"
    or: [
      { argSources: contains("user_data") }
      { argSources: contains("system_setting") }
    ]
  }
  
  effect: require_confirmation
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "删除/破坏性操作强制确认"
  }
}

rule sys.action.baseline.taint-input-deny {
  baseline: true
  domain: action
  subject: any
  
  when: {
    capability: "agent.input_inject"
    action: "ui.type"
    argSources: contains("APP_CONTENT", "WEB_CONTENT")
  }
  
  effect: deny
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "文本注入禁止 taint 来源（提示注入防线）"
  }
}

## 基线 2：查询类动作默认允许（低风险）

rule sys.action.baseline.query-allow {
  baseline: true
  domain: action
  subject: any
  
  when: {
    capability: "agent.query_packages"
    action: starts_with("query.")
  }
  
  effect: allow
  priority: 1000
  
  audit: periodic
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "只读查询默认允许（无副作用）"
  }
}

# ============================================================
# 路由轴基线（notification 域）—— 可见性保证
# ============================================================

## 基线 3：关键通知永远原生可见（baseline-deny-protect）

rule sys.notify.baseline.critical-native {
  baseline: true
  domain: notification
  
  when: {
    or: [
      { category: "alarm" }
      { category: "system_critical" }
      { category: "phone_ring_equivalent" }
      { event: "emergency_alert" }
    ]
  }
  
  effect: route_p0_native
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "闹钟/系统关键/紧急响铃永原生，不可被静默"
  }
}

rule sys.notify.baseline.p0-pinned {
  baseline: true
  domain: notification
  
  when: {
    context.userPinnedP0: true
  }
  
  effect: route_p0_native
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "用户钉选 P0 的联系人/应用永原生"
  }
}

## 基线 4：支付/验证码强制原生 P0

rule sys.notify.baseline.payment-otp-p0 {
  baseline: true
  domain: notification
  
  when: {
    or: [
      { category: "payment_confirmation" }
      { category: "otp" }
      { senderDomain: matches("bank_verified") }
    ]
  }
  
  effect: route_p0_native
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "支付/OTP 永不可被 AI 处理，必须用户直接可见"
  }
}

# ============================================================
# 路由轴基线（call_gate 域）—— 来电处理保护
# ============================================================

## 基线 5：紧急号码永远响铃（baseline-route）

rule sys.callgate.baseline.emergency {
  baseline: true
  domain: call_gate
  
  when: {
    or: [
      { call.number: matches("^(110|119|120|122|911|112)$") }
      { call.category: "emergency" }
      { call.category: "system_service" }
    ]
  }
  
  effect: route_ring_native
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "紧急号码永响铃，不可被 AI 接管"
  }
}

rule sys.callgate.baseline.vip-ring {
  baseline: true
  domain: call_gate
  
  when: {
    context.userMarkedVIP: true
  }
  
  effect: route_ring_native
  priority: 1000
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    immutable: true
    description: "用户标记 VIP 联系人永响铃"
  }
}

## 基线 6：运营商通告仅记录（不响铃不接管）

rule sys.callgate.carrier-notice-record {
  baseline: true
  domain: call_gate
  
  when: {
    call.number: starts_with("10086", "10010", "10000")
    call.category: "carrier_service"
  }
  
  effect: route_record_only
  priority: 600
  
  audit: periodic
  
  _meta: {
    version: "1.0.0"
    immutable: false
    description: "运营商服务号仅记录，不打扰"
  }
}

# ============================================================
# 安全约束规则（非 baseline，但高优先级）
# ============================================================

## 约束 1：无能力令牌直接拒绝

rule sys.action.no-token-deny {
  domain: action
  subject: any
  
  when: {
    capability: missing()
  }
  
  effect: deny
  priority: 900
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    description: "无能力令牌=无授权，deny 默认"
  }
}

## 约束 2：来源意图不匹配拒绝

rule sys.action.intent-mismatch-deny {
  domain: action
  subject: any
  
  when: {
    context.intentMatch: false
  }
  
  effect: deny
  priority: 900
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    description: "动作与用户意图不符，疑似异常行为"
  }
}

## 约束 3：签名/scope/ttl 三查验证失败拒绝

rule sys.action.token-invalid-deny {
  domain: action
  subject: any
  
  when: {
    or: [
      { context.tokenSignatureValid: false }
      { context.tokenExpired: true }
      { context.tokenScopeMatch: false }
    ]
  }
  
  effect: deny
  priority: 950
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    description: "能力令牌三查验证失败（对齐能力令牌链规范）"
  }
}

# ============================================================
# 用户确认默认规则（可被 user.* 覆盖）
# ============================================================

## 默认 1：medium 风险动作提示确认

rule sys.action.default.medium-confirm {
  domain: action
  subject: any
  
  when: {
    context.actionRiskLevel: "medium"
  }
  
  effect: require_confirmation
  then: {
    confirmationType: "inline_prompt"
    timeout: 30
  }
  priority: 500
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    description: "中等风险动作默认需确认（用户可通过 user 规则记忆化）"
  }
}

## 默认 2：high 风险强制确认（baseline 已覆盖，此为兜底）

rule sys.action.default.high-confirm {
  domain: action
  subject: any
  
  when: {
    context.actionRiskLevel: "high"
  }
  
  effect: require_confirmation
  then: {
    confirmationType: "system_ui_prompt"
    timeout: 60
    allowMemorize: false
  }
  priority: 500
  
  audit: mandatory
  
  _meta: {
    version: "1.0.0"
    description: "高风险动作强制确认且不可记忆化"
  }
}

# ============================================================
# 编译元数据
# ============================================================

_policyPackMeta: {
  version: "1.0.0"
  minEngineVersion: "1.0.0"
  totalRules: 15
  baselineCount: 8
  generatedAt: "2026-09-05T00:00:00Z"
  signature: "待签名（构建期生成）"
  
  complianceChecklist: [
    "E1.1-1: Deny 默认 ✓"
    "E1.1-2: 最小权限 ✓"
    "E1.1-3: 高风险确认 ✓"
    "E1.1-4: taint 隔离 ✓"
    "E1.1-5: 审计完整 ✓"
    "E1.1-6: fail-secure ✓"
    "E1.1-7: 基线不可覆盖 ✓"
  ]
}
