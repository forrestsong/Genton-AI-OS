package com.android.server.ainative;

import android.content.Context;
import android.os.SystemClock;
import android.os.RemoteException;
import android.util.Slog;

import com.ainative.decision.DecisionRequest;
import com.ainative.decision.DecisionResult;
import com.ainative.decision.IAgentDecisionService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 本地决策模型服务（B5.3）—— NeoHorse-Jev-4B prefill-only 结构化决策
 *
 * 旗舰版/通用版双后端：
 *  - 旗舰版（第五代骁龙8至尊版 / 天玑9500 / Exynos 2500，decision_local_capable=true）：
 *    端侧本地推理（vLLM/SGLang/原生 neohorse_decision runtime，经 LocalDecisionBackend 接入）
 *  - 通用版（其余机型，decision_local_capable=false）：
 *    经 IAgentInferenceRouter(TIER_CLOUD) → CloudBroker /v1/decision 云端决策 API
 *
 * 三类决策：Choice（候选选择）/ Noul（是非判定）/ Score（分级评分）—— 均带概率，无自由文本生成。
 *
 * 红线：模型只出"带概率的建议"；授权/deny 默认/路由常量仍由策略引擎（A7）确定性裁决；
 * 决策结果全量落审计链（模型版本+输入摘要+概率最小集，不含明文上下文）。
 *
 * 降级链（双版本通用）：本地不可用 → 云端（若允许）→ 规则/显式路径（statusCode + fallbackReason）。
 */
public class DecisionService extends IAgentDecisionService.Stub {
    private static final String TAG = "DecisionService";
    private static final String DECISION_VERSION = "0.2";
    private static final String THRESHOLD_FILE = "decision_thresholds_v1.json";
    private static final String CAPABILITY_FILE = "device_capability_table_v1.json";

    private final Context mContext;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(2);

    // 旗舰 SoC 白名单（本地推理能力位）
    private static final String[] FLAGSHIP_SOC_MARKERS = {
        "sm8850",          // 第五代骁龙 8 至尊版（Snapdragon 8 Elite Gen 5）
        "mt6991",          // 天玑 9500
        "exynos2500",      // Exynos 2500
    };

    private volatile boolean mLocalCapable;       // decision_local_capable（编译期+运行时判定）
    private volatile boolean mLocalModelLoaded;
    private volatile String mThresholdVersion = "unset";
    private volatile String mBackend = "unknown";  // local | cloud

    // 决策缓存（同 state+scenario 短时复用，低风险场景）
    private final ConcurrentHashMap<String, CachedDecision> mCache = new ConcurrentHashMap<>();

    // 计量（B5.2 协议：token/energy 入 ACB）
    private long mTotalTokens;
    private long mTotalCalls;

    // 云端路由（经推理路由 TIER_CLOUD 出网；注入点，真机联调时绑定）
    private final Object mRouterBinder = new Object();

    public DecisionService(Context context) {
        mContext = context;
        determineBackend();
    }

    /**
     * 后端判定：按机型能力位（decision_local_capable）选择本地/云端。
     * 编译期由 BoardConfig 注入 AINATIVE_DECISION_RUNTIME；运行时以能力位表为准。
     */
    private void determineBackend() {
        String runtime = SystemPropertiesCompat.get("persist.ainative.decision.runtime", "");
        if (runtime.equals("local")) {
            mLocalCapable = true;
        } else if (runtime.equals("cloud")) {
            mLocalCapable = false;
        } else {
            // 能力位表判定（A12.2b 交付物 device_capability_table_v1.json）
            mLocalCapable = isLocalCapableFromTable() || isFlagshipSoC();
        }
        mBackend = mLocalCapable ? "local" : "cloud";
        Slog.i(TAG, "Decision backend = " + mBackend + " (localCapable=" + mLocalCapable + ")");
        loadThresholds();
    }

    private boolean isFlagshipSoC() {
        String soc = SystemPropertiesCompat.get("ro.board.platform", "");
        for (String marker : FLAGSHIP_SOC_MARKERS) {
            if (soc.contains(marker)) return true;
        }
        // 兜底：读取 ro.soc.model / ro.soc.manufacturer
        String model = SystemPropertiesCompat.get("ro.soc.model", "").toLowerCase();
        return model.contains("snapdragon 8 elite gen 5")
                || model.contains("dimensity 9500")
                || model.contains("exynos 2500");
    }

    private boolean isLocalCapableFromTable() {
        try {
            File f = new File("/vendor/ainative/config/" + CAPABILITY_FILE);
            if (!f.exists()) return false;
            JSONObject table = new JSONObject(readFile(f));
            String device = SystemPropertiesCompat.get("ro.product.device", "");
            JSONArray devices = table.getJSONArray("devices");
            for (int i = 0; i < devices.length(); i++) {
                JSONObject d = devices.getJSONObject(i);
                if (d.optString("device").equals(device)) {
                    return d.optBoolean("decision_local_capable", false);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "capability table read failed", e);
        }
        return false;
    }

    private void loadThresholds() {
        try {
            File f = new File("/vendor/ainative/config/" + THRESHOLD_FILE);
            if (!f.exists()) f = new File("/system/etc/ainative/" + THRESHOLD_FILE);
            if (f.exists()) {
                JSONObject t = new JSONObject(readFile(f));
                mThresholdVersion = t.optString("version", "unset");
            }
        } catch (Exception e) {
            Slog.w(TAG, "threshold load failed", e);
        }
    }

    private String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileReader r = new FileReader(f)) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ==================== IAgentDecisionService ====================

    @Override
    public DecisionResult choose(DecisionRequest request) {
        return dispatch(request, "choice");
    }

    @Override
    public DecisionResult judge(DecisionRequest request) {
        return dispatch(request, "noul");
    }

    @Override
    public DecisionResult score(DecisionRequest request) {
        return dispatch(request, "score");
    }

    @Override
    public int getBackendStatus() {
        if (mLocalCapable && mLocalModelLoaded) return 0;       // 本地就绪
        if (!mLocalCapable) return 1;                            // 云端可用
        return 2;                                                // 本地不可用→降级云端
    }

    @Override
    public String getThresholdVersion() {
        return mThresholdVersion;
    }

    private DecisionResult dispatch(DecisionRequest request, String type) {
        long start = SystemClock.elapsedRealtimeNanos();
        mTotalCalls++;

        // 0. 敏感约束：sensitive → 一律走规则路径，禁止云端
        if (hasConstraint(request, "sensitive")) {
            return ruleFallback(request, type, "sensitive_constraint");
        }
        // 1. 缓存命中（低风险 + 未强制在线）
        if (!hasConstraint(request, "force_online")) {
            String key = cacheKey(request, type);
            CachedDecision cached = mCache.get(key);
            if (cached != null && cached.validUntilNanos > start) {
                return cached.result;
            }
        }
        // 2. 预算检查（B5.2 计量联动）
        if (request.budgetMicros > 0 && estimateCostMicros(request) > request.budgetMicros) {
            return err(request, type, 3, "budget_exceeded");
        }
        // 3. 后端选择与执行
        DecisionResult result;
        if (mLocalCapable) {
            result = localInfer(request, type);
            if (result.statusCode == 1 && !hasConstraint(request, "cloud_denied")) {
                result = cloudInfer(request, type);   // 本地不可用→降级云端
            }
        } else {
            result = cloudInfer(request, type);
        }
        result.latencyMicros = (SystemClock.elapsedRealtimeNanos() - start) / 1000;
        // 4. 缓存（仅 OK 且非敏感）
        if (result.statusCode == 0) {
            mCache.put(cacheKey(request, type),
                    new CachedDecision(result, start + TimeUnit.SECONDS.toNanos(2)));
        }
        // 5. 审计（A5.2 链：模型版本+输入摘要+概率最小集；含敏感字段时仅记摘要）
        audit(result, request, type);
        return result;
    }

    /** 旗舰版本地推理（B5.1 推理运行时接入点；真机联调绑定 vLLM/SGLang/原生 runtime） */
    private DecisionResult localInfer(DecisionRequest request, String type) {
        if (!mLocalModelLoaded) {
            // 按需加载（冷启预算入 ACB）；占位：真实加载由 B5.1 LocalDecisionBackend 提供
            mLocalModelLoaded = tryLoadLocalModel();
        }
        if (!mLocalModelLoaded) {
            return err(request, type, 1, "local_model_unavailable");
        }
        // prefill-only 推理（占位实现：接入 neohorse_decision runtime 后替换为真实前向）
        DecisionResult r = new DecisionResult();
        r.scenario = request.scenario;
        r.backend = "local";
        r.modelVersion = 1;
        r.tokenUsed = estimateTokens(request);
        mTotalTokens += r.tokenUsed;
        r.statusCode = 0;
        r.fallbackReason = "";
        // TODO(B5.1): LocalDecisionBackend.infer(request, type) → selected/confidence/probabilities
        return r;
    }

    /** 通用版云端推理（经 IAgentInferenceRouter TIER_CLOUD → CloudBroker /v1/decision，见 G1.1b） */
    private DecisionResult cloudInfer(DecisionRequest request, String type) {
        if (hasConstraint(request, "offline_ok")) {
            return err(request, type, 4, "cloud_unavailable_offline");
        }
        if (hasConstraint(request, "cloud_denied")) {
            return ruleFallback(request, type, "cloud_denied");
        }
        // TODO(B5.3/G1.1b): 绑定 IAgentInferenceRouter.infer(TIER_CLOUD) → CloudBroker POST /v1/decision
        //  请求：{type: choice|noul|score, scenario, state(脱敏), candidates, question, constraints,
        //        privacy:{mode}, budget:{costCeilingMicros}, thresholdVersion}
        //  响应：DecisionResult{selected, confidence, probabilities, modelVersion, backend:"cloud",
        //        usage{inputTokens,outputTokens,costMicros,ts,signature}, status{code,reason}}
        //  安全：mTLS + 会话票据（G2.1/G2.2）+ preflightEgress 脱敏最小字段
        //  usage.signature 验签后写入 ACB AgentBudget 对账（G1.2）
        //  错误码映射：401/403→statusCode=4 规则路径；429→3 BUDGET_EXCEEDED；
        //             422→4 规则路径；503/504→4/5 降级规则路径
        //  参考实现：tools/cloud/decision_service_stub.py（契约与错误码已对齐）
        DecisionResult r = new DecisionResult();
        r.scenario = request.scenario;
        r.backend = "cloud";
        r.modelVersion = 1;
        r.tokenUsed = estimateTokens(request);
        mTotalTokens += r.tokenUsed;
        r.statusCode = 0;
        r.fallbackReason = "";
        return r;
    }

    private boolean tryLoadLocalModel() {
        // TODO(B5.3): 校验模型包签名 → 加载到 NPU/内存（旗舰版）；失败返回 false 走云端降级
        return false;  // 占位：真机模型包就绪后返回 true
    }

    private DecisionResult ruleFallback(DecisionRequest request, String type, String reason) {
        DecisionResult r = new DecisionResult();
        r.scenario = request.scenario;
        r.backend = "rule";
        r.statusCode = 0;
        r.fallbackReason = reason;
        r.selected = "rule_path";
        r.confidence = 0f;
        r.modelVersion = 0;
        r.tokenUsed = 0;
        return r;
    }

    private DecisionResult err(DecisionRequest request, String type, int code, String reason) {
        DecisionResult r = new DecisionResult();
        r.scenario = request.scenario;
        r.backend = mBackend;
        r.statusCode = code;
        r.fallbackReason = reason;
        r.modelVersion = 0;
        r.tokenUsed = 0;
        return r;
    }

    private boolean hasConstraint(DecisionRequest request, String name) {
        if (request.constraints == null) return false;
        for (String c : request.constraints) {
            if (name.equals(c)) return true;
        }
        return false;
    }

    private long estimateTokens(DecisionRequest request) {
        long base = (request.state == null ? 0 : request.state.length() / 2);
        if (request.candidates != null) base += request.candidates.length * 8;
        return Math.max(1, base);
    }

    private long estimateCostMicros(DecisionRequest request) {
        // 粗略成本估算：token × 单位成本（旗舰版本地≈0；云端按档位）
        long tokens = estimateTokens(request);
        if (mLocalCapable) return tokens / 1000;           // 本地近似 0
        return tokens * 2;                                 // 云端档位估算（对账 G1.2 为准）
    }

    private String cacheKey(DecisionRequest request, String type) {
        return type + "|" + request.scenario + "|"
                + (request.state == null ? "" : request.state.hashCode()) + "|"
                + (request.question == null ? "" : request.question);
    }

    private void audit(DecisionResult result, DecisionRequest request, String type) {
        // TODO(A5.3): 写入 IAgentAuditService 审计链
        // 最小集：scenario/type/backend/modelVersion/confidence/statusCode/latencyMicros
        // 敏感输入只记摘要（hash），不含明文上下文
        Slog.d(TAG, "audit: " + type + "/" + request.scenario
                + " backend=" + result.backend + " status=" + result.statusCode);
    }

    // ==================== 统计/治理 ====================

    public JSONObject dumpStats() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", DECISION_VERSION);
            o.put("backend", mBackend);
            o.put("localCapable", mLocalCapable);
            o.put("localModelLoaded", mLocalModelLoaded);
            o.put("thresholdVersion", mThresholdVersion);
            o.put("totalCalls", mTotalCalls);
            o.put("totalTokens", mTotalTokens);
            o.put("cacheSize", mCache.size());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static class CachedDecision {
        final DecisionResult result;
        final long validUntilNanos;
        CachedDecision(DecisionResult r, long validUntilNanos) {
            this.result = r;
            this.validUntilNanos = validUntilNanos;
        }
    }

    /** SystemProperties 只读封装（避免直接依赖隐藏 API 时的编译期差异） */
    private static final class SystemPropertiesCompat {
        static String get(String key, String def) {
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                return (String) c.getMethod("get", String.class, String.class)
                        .invoke(null, key, def);
            } catch (Exception e) {
                return def;
            }
        }
    }
}
