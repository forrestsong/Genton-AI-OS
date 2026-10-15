package com.ainative.inference;

import com.ainative.inference.InferenceRequest;
import com.ainative.inference.InferenceCapabilities;
import com.ainative.inference.InferenceStats;
import com.ainative.inference.EgressPlan;
import com.ainative.inference.AttestationReport;
import com.ainative.inference.IInferenceCallback;

/**
 * IAgentInferenceRouter - AI Runtime 推理路由服务
 * 
 * AI Native OS 本地推理运行时与端云路由决策服务
 * 职责：
 * 1. NPU delegate 集成（本地推理）
 * 2. 模型加载/mmap（内存映射加载大模型）
 * 3. Prefix 缓存与 KV 复用（降低重复计算）
 * 4. 流式输出（逐 token 流式生成）
 * 5. 路由决策（本地 tiny/large/云端智能路由）
 * 6. 端云调度（隐私/延迟/成本/网络状态综合决策）
 * 7. 用量计量（token/energy 真实回传，防虚报）
 * 8. 出网前预检（隐私审计与透明）
 * 9. 设备远程证明（云端安全认证）
 * 
 * 三级档位：
 * - TIER_LOCAL_TINY: 常驻小模型（唤醒/意图识别/路由），<2W 功耗
 * - TIER_LOCAL_LARGE: 端侧大模型（NPU 有余量且离线可用）
 * - TIER_CLOUD: 云端推理（需授权+证明+白名单）
 * 
 * 对应任务：B5.1（本地推理）+ A9.1（云端路由与安全）
 * 前置依赖：无（基础服务）
 * 草案：D9 §5a/§6f（端云调度与推理路由接口草案）
 * 里程碑：A2
 * 
 * 权限要求：
 * - USE_INFERENCE (signature|privileged) - 使用推理能力
 */
interface IAgentInferenceRouter {
    // 状态码
    const int STATUS_OK = 0;
    const int STATUS_DENIED = 1;                // 约束冲突或未授权
    const int STATUS_NO_ROUTE = 2;              // 无可用模型且云端不可达
    const int STATUS_BUDGET_EXCEEDED = 3;       // 预算超限
    const int STATUS_ATTESTATION_FAILED = 4;    // 远程证明失败
    const int STATUS_INTERNAL_ERROR = -1;
    
    // 模型档位（路由决策输出）
    const int TIER_LOCAL_TINY = 0;    // 常驻小模型（唤醒/意图识别/路由）
    const int TIER_LOCAL_LARGE = 1;   // 端侧大模型（NPU 有余量/离线）
    const int TIER_CLOUD = 2;         // 云端（最小字段+授权+证明）
    
    /**
     * 获取推理能力
     * @return InferenceCapabilities 能力描述（档位支持/模型列表/NPU 状态）
     */
    InferenceCapabilities getCapabilities();
    
    /**
     * 流式推理
     * 路由决策：根据请求约束、意图复杂度、网络状态等决定本地或云端
     * @param request 推理请求
     * @param callback 流式回调
     * @param requestId 输出请求 ID（单元素数组，取 requestId[0]；用于 cancel）
     * @return 0 成功，负数错误码
     */
    int infer(in InferenceRequest request, in IInferenceCallback callback, out String[] requestId);
    
    /**
     * 取消推理
     * @param requestId 请求 ID
     * @return 0 成功，负数错误码
     */
    int cancel(in String requestId);
    
    /**
     * 获取推理统计
     * @return InferenceStats 统计信息（电量/网络/队列/用量）
     */
    InferenceStats getStats();
    
    /**
     * 出网前预检（隐私审计）
     * 返回将外发的字段清单和脱敏状态，供审计与用户透明
     * @param requestId 请求 ID
     * @param plan 输出：出网计划（字段清单/脱敏字段/是否本地完成）
     * @return 0 成功，负数错误码
     */
    int preflightEgress(in String requestId, out EgressPlan plan);
    
    /**
     * 设备远程证明
     * 返回设备远程证明 quote，供 CloudGateway 在每次会话/周期调用
     * @return AttestationReport 证明报告（quote/签名/时间戳）
     */
    AttestationReport attestDevice();
}
