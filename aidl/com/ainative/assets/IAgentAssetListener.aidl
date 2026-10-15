package com.ainative.assets;

import com.ainative.assets.AssetUpdateInfo;

/**
 * 资产变更监听器
 * 
 * 用于通知订阅者资产状态变更：
 * - 新增/升级/停用/回滚
 * - App版本漂移导致资产失效
 * - 回归测试状态变更
 */
interface IAgentAssetListener {
    /**
     * 资产变更回调
     * @param info 变更信息
     */
    oneway void onAssetChanged(in AssetUpdateInfo info);
}
