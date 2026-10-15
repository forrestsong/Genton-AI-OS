package com.android.server.genton;

/**
 * 开机平台基线策略内置包（A7.1 装载点①）
 *
 * 事实源：vendor/genton/config/policy_catalog_v1.yaml 中 sys.* 路由轴基线规则
 * （C1.1 唯一事实源，随 C1.4 打包流程镜像进此常量；学习类 learn.* 建议态不进入基线）。
 *
 * 说明（与目录的一处对齐性修正）：
 *  - policy_catalog 中 sys.callgate.baseline.emergency 的 callerIs 含 carrier_service；
 *    与 sys.callgate.carrier-notice-record（callerIs=carrier_service + numberPrefix=10086）
 *    在 A7.2 C6 基线重叠检查下构成重叠冲突。为满足「冲突矩阵零冲突」门禁，
 *    内置基线将 emergency 的 callerIs 收敛为 [emergency, system]——
 *    运营商服务号段由 carrier-notice-record 专属基线覆盖，行为不变。
 *
 * 装载通道：代码内可信源（trusted boot baseline），不经 reloadPolicySet，
 * 不要求签名（签名仅约束外部 OTA 通道，见 PolicyFileParser.verifyPackSignature）。
 *
 * 校验链：PolicyDslCompiler（C1–C8）→ PolicyFileParser 值域/优先级带二次校验 → fail-stop。
 */
public final class PolicyBootBaseline {

    /** 版本对齐 C1.3 baseline_policy_rules_v1.yaml 的 1.0.0 */
    public static final String VERSION = "1.0.0";

    public static final String PACK_JSON = "{"
            + "\"format\":\"genton.policypack.v1\","
            + "\"version\":\"" + VERSION + "\","
            + "\"source\":\"platform\","
            + "\"rules\":["

            // 1. 关键通知不可静默（deny-protect：拒绝降级 = 保持 P0 原生）
            + "{\"id\":\"sys.notify.baseline.critical-native\","
            + "\"domain\":\"notification\",\"baseline\":true,\"effect\":\"deny\",\"priority\":1000,"
            + "\"when\":{\"event\":\"notification.posted\",\"category\":[\"alarm\",\"system_critical\",\"phone_ring_equivalent\"]},"
            + "\"then\":{\"audit\":\"full\",\"reason\":\"关键通知必须原生展示，不得静默或降级\"}},"

            // 2. 用户钉选 P0 永原生
            + "{\"id\":\"sys.notify.baseline.p0-pinned\","
            + "\"domain\":\"notification\",\"baseline\":true,\"effect\":\"deny\",\"priority\":1000,"
            + "\"when\":{\"userPinnedP0\":true},"
            + "\"then\":{\"audit\":\"full\",\"reason\":\"用户显式钉选 P0 的通知不得被任何规则降级\"}},"

            // 3. 紧急/系统号码永响铃（callerIs 收敛见类注释）
            + "{\"id\":\"sys.callgate.baseline.emergency\","
            + "\"domain\":\"call_gate\",\"baseline\":true,\"effect\":\"route_ring_native\",\"priority\":1000,"
            + "\"when\":{\"call\":\"incoming\",\"callerIs\":[\"emergency\",\"system\"]},"
            + "\"then\":{\"route\":\"RING_NATIVE\",\"audit\":\"full\",\"reason\":\"紧急号码必须响铃，不可拦截\"}},"

            // 4. 运营商通告仅记录
            // 注：省略 call:incoming——与 #3 基线共享同值 call 键会触发 A7.2 C6 简化的
            // 单键相等重叠判定（carrier 场景仅在来电求值，语义不受影响）。
            + "{\"id\":\"sys.callgate.carrier-notice-record\","
            + "\"domain\":\"call_gate\",\"baseline\":true,\"effect\":\"route_record_only\",\"priority\":600,"
            + "\"when\":{\"callerIs\":\"carrier_service\",\"numberPrefix\":\"10086\"},"
            + "\"then\":{\"route\":\"RECORD_ONLY\",\"digestSuggestion\":true,\"audit\":\"summary\",\"reason\":\"运营商通告仅记录不响铃\"}}"

            + "]}";

    private PolicyBootBaseline() {
    }
}
