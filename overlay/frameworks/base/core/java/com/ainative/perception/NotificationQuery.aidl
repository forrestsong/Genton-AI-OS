package com.ainative.perception;

/**
 * NotificationQuery - 通知查询请求
 * 
 * 用于查询通知栈，支持包名过滤、重要性过滤、PII 掩码等。
 */
parcelable NotificationQuery {
    String[] packageFilter;        // 为空表示全部（受限清单除外）
    int importanceMin = 2;         // 对齐 NotificationManager.IMPORTANCE_*（默认 LOW 以上）
    boolean includeText = true;    // false 则只给标题/来源，进一步省敏
    boolean includeActions = true;
    boolean maskPii = true;
    int maskMode = 1;              // 0=NONE, 1=PARTIAL, 2=FULL
    long maxItems = 50;
}