package com.genton.perception;

import android.graphics.Rect;

/**
 * UiNode - UI 树节点
 *
 * 承载 WindowManager/View 层级合成后的单个节点。字段与
 * AgentUiTreeService 的节点结构一一对应，保证快照不丢失安全与定位信息：
 * - resourceId 供动作定位（比坐标更稳）；
 * - visible / flagSecure / secureReason / piiMasked 供调用方判断
 *   "该节点是否可见、是否为安全窗口、文本是否已被掩码"。
 *
 * children 为自引用列表（AIDL 允许结构化 parcelable 自引用），
 * 递归承载整棵 UI 树。
 */
parcelable UiNode {
    String nodeId;              // 节点唯一标识
    String className;           // 节点类名（窗口节点为 "window" / 根节点为 "screen"）
    String text;                // 节点文本内容（已按 maskPii 处理）
    String contentDesc;         // 内容描述（可访问性）
    String resourceId;          // 资源 ID（如 "com.x:id/btn"）
    Rect bounds;                // 边界矩形（支持 centerX()/centerY()）
    boolean clickable;          // 是否可点击
    boolean scrollable;         // 是否可滚动
    boolean enabled;            // 是否启用
    boolean focused;            // 是否获得焦点
    boolean visible;            // 是否可见
    boolean flagSecure;         // 是否 FLAG_SECURE 窗口
    int zOrder;                 // 窗口层级（仅窗口节点有效）
    boolean piiMasked;          // 文本是否已被 PII 掩码
    String secureReason;        // 安全原因（如 "FLAG_SECURE"）
    String packageName;         // 所属包名
    List<UiNode> children;      // 子节点（叶子节点为 null）
}
