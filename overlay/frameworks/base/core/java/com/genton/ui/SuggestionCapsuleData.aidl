// SuggestionCapsuleData.aidl
// 建议胶囊数据
package com.genton.ui;

/**
 * 建议胶囊数据
 * 
 * 主动服务建议（NORMAL 级打扰，受免打扰约束）
 */
parcelable SuggestionCapsuleData {
    /**
     * 胶囊 ID（唯一标识）
     */
    String capsuleId;
    
    /**
     * 标题（简短）
     */
    String title;
    
    /**
     * 摘要（脱敏，不含敏感信息）
     */
    String summaryMasked;
    
    /**
     * 建议的动作命令（用户点击后作为 UserTurn.content 提交）
     */
    String actionCommand;
    
    /**
     * 过期时间戳（纳秒，SystemClock.elapsedRealtimeNanos）
     * 
     * 超过此时间自动移除胶囊
     */
    long expiresAtRealtimeNanos;
    
    /**
     * 优先级
     * 
     * 0 = NORMAL（默认）
     * 1 = HIGH（重要建议，免打扰模式下仍显示）
     */
    int priority;
}
