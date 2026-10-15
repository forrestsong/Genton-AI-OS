package com.genton.perception;

import com.genton.perception.UiSnapshot;
import com.genton.perception.UiTreeRequest;
import com.genton.perception.UiNode;
import com.genton.perception.IAgentUiTreeListener;

/**
 * IAgentUiTreeService
 * AI Native OS 系统服务接口
 */
interface IAgentUiTreeService {

    UiSnapshot captureUiTree(in UiTreeRequest request);

    void subscribeUiChanges(in IAgentUiTreeListener listener);

    void unsubscribeUiChanges(in IAgentUiTreeListener listener);
    
    /**
     * 根据节点 ID 查询单个 UI 节点
     * 
     * @param nodeId 节点唯一标识
     * @return 节点信息，如果不存在返回 null
     */
    UiNode getNodeById(in String nodeId);
    
    /**
     * 获取当前 UI 树序列号
     * 
     * 用于检测 UI 树是否已过时（stale detection）
     * @return 序列号，每次 UI 树变更时递增
     */
    long getCurrentSequence();

}