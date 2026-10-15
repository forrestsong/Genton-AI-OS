package com.ainative.inference;

import com.ainative.inference.InferenceChunk;

/**
 * IInferenceCallback - 推理回调接口
 * 
 * 流式推理输出回调，支持增量文本/工具调用/用量估计
 */
interface IInferenceCallback {
    /**
     * 路由决策完成
     * @param requestId 请求 ID
     * @param tier 实际执行档位（TIER_LOCAL_TINY/TIER_LOCAL_LARGE/TIER_CLOUD）
     */
    oneway void onRouteChosen(in String requestId, int tier);
    
    /**
     * 推理输出块
     * @param requestId 请求 ID
     * @param chunk 输出块（文本/工具调用/用量）
     */
    oneway void onChunk(in String requestId, in InferenceChunk chunk);
    
    /**
     * 推理完成
     * @param requestId 请求 ID
     * @param status 状态码（0=成功，负数=错误）
     * @param error 错误信息（成功时为 null）
     */
    oneway void onDone(in String requestId, int status, in String error);
}
