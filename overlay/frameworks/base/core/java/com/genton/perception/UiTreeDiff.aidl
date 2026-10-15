package com.genton.perception;

import com.genton.perception.UiNode;

/**
 * UiTreeDiff - UI 树增量差异
 *
 * 服务端实现：AgentUiTreeService.computeDiff()（当前为骨架，返回空的三个列表）。
 * 由 UiSnapshot.incrementalDiff 承载。
 */
parcelable UiTreeDiff {
    /** 新增节点 */
    List<UiNode> addedNodes;

    /** 移除节点 */
    List<UiNode> removedNodes;

    /** 变更节点 */
    List<UiNode> changedNodes;
}
