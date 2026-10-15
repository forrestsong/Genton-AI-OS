package com.genton.policy;

/**
 * 策略监听器（订阅策略变更/令牌撤销）
 * 
 * @hide
 */
interface IPolicyListener {
    /**
     * 策略包已更新
     * 
     * @param bundleVersion 新策略版本
     */
    oneway void onPolicyBundleUpdated(String bundleVersion);
    
    /**
     * 令牌已撤销
     * 
     * @param agentId Agent ID
     * @param intentId 意图 ID（null=全部）
     */
    oneway void onTokensRevoked(String agentId, String intentId);
}
