package com.genton.assets;

import com.genton.assets.AssetDescriptor;

/**
 * 资产解析结果
 */
parcelable ResolvedAsset {
    String assetId;
    String version;
    int kind;                       // KIND_SKILL | KIND_DMI | KIND_MACRO
    String capability;              // 能力名（交策略引擎评估用）
    String artifactRef;             // 执行中介取载荷的引用（先验签后执行）
    String[] fallbackChain;         // 备选资产ID（DMI失效 → macro → ...）
    AssetDescriptor descriptor;     // 含风险/确认提示
}
