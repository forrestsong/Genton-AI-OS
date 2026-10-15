// IAssetStateListener.aidl
package com.ainative.assets;

/**
 * 资产状态变更监听器
 */
oneway interface IAssetStateListener {
    /**
     * 资产注册
     * 
     * @param assetId 资产 ID
     * @param assetType 资产类型
     */
    void onAssetRegistered(in String assetId, in String assetType);
    
    /**
     * 资产启用
     * 
     * @param assetId 资产 ID
     * @param stagedRatio 灰度比例
     */
    void onAssetEnabled(in String assetId, float stagedRatio);
    
    /**
     * 资产停用
     * 
     * @param assetId 资产 ID
     * @param reason 停用原因（0=manual, 1=auto_failure）
     */
    void onAssetDisabled(in String assetId, int reason);
    
    /**
     * 资产更新
     * 
     * @param assetId 资产 ID
     * @param oldVersion 旧版本
     * @param newVersion 新版本
     */
    void onAssetUpdated(in String assetId, in String oldVersion, in String newVersion);
}
