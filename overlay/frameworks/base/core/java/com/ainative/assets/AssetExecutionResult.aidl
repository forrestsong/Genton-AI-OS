// AssetExecutionResult.aidl
package com.ainative.assets;

/**
 * 资产执行结果（用于回灌统计）
 */
parcelable AssetExecutionResult {
    // 资产 ID
    String assetId;
    
    // 执行会话 ID
    String sessionId;
    
    // 是否成功
    boolean success;
    
    // 错误码（成功时为 0）
    int errorCode;
    
    // 错误描述
    @nullable String errorMessage;
    
    // 执行延迟（毫秒）
    long latencyMs;
    
    // 执行时间戳
    long executedAt;
    
    // 用户反馈（可选）
    // 0 = no_feedback, 1 = thumbs_up, -1 = thumbs_down
    int userFeedback = 0;
    
    // 扩展信息（JSON 字符串）
    @nullable String extras;
}
