package com.genton.semantics;

import android.os.Bundle;

/**
 * 实体
 * 
 * 本体层的核心数据单元，统一物化来自记忆、通知、日历、联系人、文件等来源的实体信息
 */
parcelable Entity {
    /**
     * 实体唯一标识（稳定跨来源）
     */
    String entityId;
    
    /**
     * 实体类型（ET_* 常量）
     */
    int entityType;
    
    /**
     * 规范显示名
     */
    String label;
    
    /**
     * 别名（供消歧）
     */
    String[] aliases;
    
    /**
     * 类型化属性（结构化键值；时间用毫秒 epoch）
     */
    Bundle attributes;
    
    /**
     * 抽取/合并置信度 0~1
     */
    float confidence;
    
    /**
     * 更新时间（realtime 纳秒）
     */
    long updatedAtRealtimeNanos;
    
    /**
     * 是否敏感（金融/健康/账号类，默认受限）
     */
    boolean sensitive;
    
    /**
     * 物化来源（通知/日历/记忆…），可追溯
     */
    String provenanceRef;
}
