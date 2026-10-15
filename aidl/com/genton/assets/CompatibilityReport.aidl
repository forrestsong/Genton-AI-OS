package com.genton.assets;

/**
 * 兼容性报告（诊断用）
 */
parcelable CompatibilityReport {
    String assetId;
    boolean deviceModelMatch;       // 机型匹配
    boolean appVersionMatch;        // App版本匹配
    boolean osVersionMatch;         // OS版本匹配
    boolean signatureValid;         // 签名有效
    String incompatReason;          // 不兼容原因详情
    String currentDeviceModel;      // 当前机型
    String currentAppVersion;       // 当前App版本
    String currentOsVersion;        // 当前OS版本
}
