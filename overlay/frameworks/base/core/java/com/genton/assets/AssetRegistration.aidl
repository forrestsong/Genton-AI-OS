// AssetRegistration.aidl
package com.genton.assets;

import com.genton.assets.AssetManifest;

/**
 * 资产注册信息
 */
parcelable AssetRegistration {
    // 资产清单（包含元数据）
    AssetManifest manifest;
    
    // 资产包路径或内容
    String assetPayload;
    
    // 签名（用于验签）
    byte[] signature;
    
    // 降级链（fallback chain）
    // 例如：["skill:weather_v2", "dmi:weather_api", "macro:open_weather_app"]
    String[] fallbackChain;
    
    // 发布者包名
    String publisherPackage;
    
    // 是否立即启用（false = 注册后需手动 enable）
    boolean enableImmediately = true;
    
    // 初始灰度比例（0.0-1.0）
    float initialStagedRatio = 1.0f;
}
