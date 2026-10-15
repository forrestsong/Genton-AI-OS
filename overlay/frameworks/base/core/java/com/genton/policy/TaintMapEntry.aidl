package com.genton.policy;

/**
 * Taint 映射条目（参数来源打标）
 * 
 * @hide
 */
parcelable TaintMapEntry {
    /** 参数键（如 "text"/"target"） */
    String argKey;
    
    /** 信任等级（0=TRUSTED_SYSTEM, 1=USER, 2=APP_CONTENT, 3=WEB_CONTENT） */
    int trust;
}
