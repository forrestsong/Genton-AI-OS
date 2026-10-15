// IAgentAssetLibraryService.aidl
// 资产目录服务接口
package com.ainative.assets;

import com.ainative.assets.AssetRegistration;
import com.ainative.assets.AssetQuery;
import com.ainative.assets.AssetManifest;
import com.ainative.assets.AssetExecutionResult;
import com.ainative.assets.IAssetStateListener;

/**
 * 资产目录服务接口
 * 
 * 职责：
 * 1. 资产注册与验签（Skill/DMI/Macro）
 * 2. 版本绑定与降级链
 * 3. Staged-enable（灰度启用）
 * 4. 失败自动停用
 * 5. 执行回灌统计
 * 
 * 实现方：system_server 平台框架
 * 调用方：Agent 大脑、第三方开发者工具
 * 
 * 对应任务：A8.1 资产目录服务端
 * 前置依赖：A1.3 (AIDL 骨架)
 * 草案来源：D11
 */
interface IAgentAssetLibraryService {
    // 状态码
    const int STATUS_OK = 0;
    const int STATUS_DENIED = 1;              // 权限拒绝
    const int STATUS_INVALID_ASSET = 2;       // 资产格式错误
    const int STATUS_SIGNATURE_FAILED = 3;    // 签名验证失败
    const int STATUS_VERSION_CONFLICT = 4;    // 版本冲突
    const int STATUS_ASSET_NOT_FOUND = 5;     // 资产不存在
    const int STATUS_INTERNAL_ERROR = -1;
    
    /**
     * 注册资产
     * 
     * @param registration 资产注册信息（包含 manifest、签名、降级链）
     * @param assetId 输出参数，返回资产 ID（单元素数组，取 assetId[0]）
     * @return STATUS_OK 成功；其他错误码
     */
    int registerAsset(in AssetRegistration registration, out String[] assetId);
    
    /**
     * 查询资产
     * 
     * @param query 查询条件（按 type/category/version）
     * @return AssetManifest 数组
     */
    AssetManifest[] queryAssets(in AssetQuery query);
    
    /**
     * 获取资产详情
     * 
     * @param assetId 资产 ID
     * @return AssetManifest，不存在返回 null
     */
    AssetManifest getAsset(in String assetId);
    
    /**
     * 启用资产（Staged-enable）
     * 
     * @param assetId 资产 ID
     * @param stagedRatio 灰度比例 0.0-1.0（1.0 = 全量）
     * @return STATUS_OK 成功；其他错误码
     */
    int enableAsset(in String assetId, float stagedRatio);
    
    /**
     * 停用资产
     * 
     * @param assetId 资产 ID
     * @param reason 停用原因（0=manual 手动；1=auto_failure 失败自动）
     * @return STATUS_OK 成功；其他错误码
     */
    int disableAsset(in String assetId, int reason);
    
    /**
     * 回灌执行结果（用于统计与自动停用）
     * 
     * @param result 执行结果（包含 assetId、success、errorCode、latencyMs）
     * @return STATUS_OK 成功；其他错误码
     */
    int reportExecutionResult(in AssetExecutionResult result);
    
    /**
     * 解析降级链（选择最佳可用资产）
     * 
     * @param intentAction 意图动作（如 "查天气"）
     * @param fallbackChain 降级链（如 ["skill:weather_v2", "dmi:weather_api", "macro:open_weather_app"]）
     * @return 最佳可用资产 ID，全部不可用返回 null
     */
    String resolveFallbackChain(in String intentAction, in String[] fallbackChain);
    
    /**
     * 订阅资产状态变更
     * 
     * @param listener 状态监听器
     */
    void subscribeStateChanges(in IAssetStateListener listener);
    
    /**
     * 取消订阅
     * 
     * @param listener 状态监听器
     */
    void unsubscribeStateChanges(in IAssetStateListener listener);
    
    /**
     * 获取资产统计
     * 
     * @param assetId 资产 ID
     * @return JSON 字符串，包含 totalExec/successCount/failureCount/avgLatencyMs
     */
    String getAssetStats(in String assetId);
}
