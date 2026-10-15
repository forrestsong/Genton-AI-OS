package com.ainative.audit;

/**
 * 链完整性状态（Chain Status）
 * 
 * 用途：
 * - 隐私仪表盘显示"链完整 ✓"
 * - 司法取证完整性证明
 * - 检测篡改攻击
 * 
 * 校验内容：
 * 1. prevHash 链完整性
 * 2. eventId 哈希正确性
 * 3. seq 单调递增无跳跃
 * 4. TEE 锚点签名有效
 */
parcelable ChainStatus {
    /**
     * 链是否完整
     * 
     * true：全部事件 prevHash 匹配，无断裂
     * false：发现断裂或篡改
     */
    boolean verified;
    
    /**
     * 校验起始序号
     */
    long fromSeq;
    
    /**
     * 校验结束序号
     */
    long toSeq;
    
    /**
     * 断裂位置（0 = 无断裂）
     * 
     * 如果 verified = false，此字段指示第一个断裂的 seq
     * 
     * 示例：
     * - brokenAtSeq = 0：链完整
     * - brokenAtSeq = 1234：seq=1234 的事件 prevHash 与前序不匹配
     */
    long brokenAtSeq;
    
    /**
     * 最近锚点引用
     * 
     * 格式：anchor_<seq>_<timestamp>
     * 
     * 指向最近一次 TEE 锚定
     * 锚点包含：
     * - 该区间的默克尔根
     * - TEE 签名
     * - 存储在安全存储中
     */
    String anchorRef;
    
    /**
     * 锚定时间戳（SystemClock.elapsedRealtimeNanos）
     * 
     * 用途：
     * - 展示"最后锚定于 X 分钟前"
     * - 判断锚定是否过期（如超过 24 小时未锚定）
     */
    long anchoredAtRealtimeNanos;
}
