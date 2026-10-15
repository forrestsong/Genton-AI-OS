package com.ainative.semantics;

/**
 * 实体变更监听器
 * 
 * 实体 upsert/删除/可见性变化；订阅方（含本体服务自身缓存层）据此失效缓存
 */
interface IEntityChangeListener {
    /**
     * 实体变化通知
     * 
     * @param entityId 变化的实体ID
     * @param changeType 变更类型：CHANGE_UPSERT/CHANGE_DELETE/CHANGE_VISIBILITY
     */
    oneway void onEntityChanged(String entityId, int changeType);
}
