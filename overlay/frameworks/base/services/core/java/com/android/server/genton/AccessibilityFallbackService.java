package com.android.server.genton;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accessibility 回退服务（Accessibility Fallback Service）
 * 
 * 职责：
 * 1. 当主 UI 树服务（WindowManager 直接读取）失败时，通过 AccessibilityService 回退捕获
 * 2. 将 AccessibilityNodeInfo 树转换为标准 UiNode 格式
 * 3. 提供与主通道相同的接口契约
 * 
 * 使用场景：
 * - WindowManager hook 失败（OEM 定制冲突）
 * - View 层级不可访问（加密应用）
 * - 系统服务异常降级
 * 
 * 安全约束：
 * - 仅 system_server 内部调用，非独立 AccessibilityService
 * - 继承 SYSTEM_UID 权限，无需用户授权
 * - 不暴露为公开服务
 * 
 * 性能要求：
 * - UI 树捕获延迟 < 100ms
 * - 节点转换开销 < 50ms
 * 
 * @see AgentUiTreeService
 * @see UiTreeConsistencyValidator
 */
public class AccessibilityFallbackService {
    private static final String TAG = "AccessibilityFallbackService";
    
    private final Context mContext;
    private final Handler mHandler;
    
    // 上次捕获的 UI 树（用于一致性对比）
    private UiTreeSnapshot mLastSnapshot;
    
    // 统计
    private long mTotalCaptures = 0;
    private long mFailedCaptures = 0;
    
    public AccessibilityFallbackService(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        
        Slog.i(TAG, "AccessibilityFallbackService initialized");
    }
    
    /**
     * 捕获当前 UI 树（通过 Accessibility）
     * 
     * @return UI 树快照，失败返回 null
     */
    public UiTreeSnapshot captureUiTree() {
        long startNanos = System.nanoTime();
        mTotalCaptures++;
        
        try {
            // 获取 AccessibilityService 实例（系统内部）
            AccessibilityNodeInfo rootNode = getRootNodeInfo();
            
            if (rootNode == null) {
                Slog.w(TAG, "Failed to get root AccessibilityNodeInfo");
                mFailedCaptures++;
                return null;
            }
            
            // 转换为标准 UiNode 格式
            UiTreeSnapshot snapshot = new UiTreeSnapshot();
            snapshot.timestamp = System.currentTimeMillis();
            snapshot.source = "accessibility";
            snapshot.rootNode = convertToUiNode(rootNode, 0);
            
            // 释放节点
            rootNode.recycle();
            
            mLastSnapshot = snapshot;
            
            long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
            Slog.d(TAG, String.format("UI tree captured via Accessibility (%dµs)", elapsedMicros));
            
            return snapshot;
            
        } catch (Exception e) {
            Slog.e(TAG, "captureUiTree failed", e);
            mFailedCaptures++;
            return null;
        }
    }
    
    /**
     * 获取窗口列表
     */
    public List<WindowInfo> captureWindows() {
        try {
            List<AccessibilityWindowInfo> windows = getAccessibilityWindows();
            List<WindowInfo> result = new ArrayList<>();
            
            for (AccessibilityWindowInfo window : windows) {
                WindowInfo info = new WindowInfo();
                info.windowId = window.getId();
                info.layer = window.getLayer();
                info.type = convertWindowType(window.getType());
                info.focused = window.isFocused();
                info.active = window.isActive();
                
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                info.bounds = bounds;
                
                result.add(info);
            }
            
            return result;
            
        } catch (Exception e) {
            Slog.e(TAG, "captureWindows failed", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 将 AccessibilityNodeInfo 转换为 UiNode
     */
    private UiNode convertToUiNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 50) {  // 防止递归过深
            return null;
        }
        
        UiNode uiNode = new UiNode();
        
        // 基础属性
        uiNode.nodeId = generateNodeId(node);
        uiNode.className = safeToString(node.getClassName());
        uiNode.packageName = safeToString(node.getPackageName());
        uiNode.text = safeToString(node.getText());
        uiNode.contentDescription = safeToString(node.getContentDescription());
        uiNode.viewIdResourceName = safeToString(node.getViewIdResName());
        
        // 边界
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        uiNode.bounds = bounds;
        
        // 状态标志
        uiNode.enabled = node.isEnabled();
        uiNode.clickable = node.isClickable();
        uiNode.longClickable = node.isLongClickable();
        uiNode.focusable = node.isFocusable();
        uiNode.focused = node.isFocused();
        uiNode.selected = node.isSelected();
        uiNode.checkable = node.isCheckable();
        uiNode.checked = node.isChecked();
        uiNode.scrollable = node.isScrollable();
        uiNode.editable = node.isEditable();
        uiNode.password = node.isPassword();
        uiNode.visibleToUser = node.isVisibleToUser();
        
        // 子节点
        int childCount = node.getChildCount();
        uiNode.children = new ArrayList<>(childCount);
        
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                UiNode childNode = convertToUiNode(child, depth + 1);
                if (childNode != null) {
                    uiNode.children.add(childNode);
                }
                child.recycle();
            }
        }
        
        return uiNode;
    }
    
    /**
     * 生成节点 ID（基于层级路径）
     */
    private String generateNodeId(AccessibilityNodeInfo node) {
        // 使用 hashCode 作为临时 ID（实际应基于 View 层级路径）
        return "acc_" + Integer.toHexString(System.identityHashCode(node));
    }
    
    /**
     * 安全转字符串
     */
    private String safeToString(CharSequence cs) {
        return cs != null ? cs.toString() : "";
    }
    
    /**
     * 转换窗口类型
     */
    private String convertWindowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "APPLICATION";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "INPUT_METHOD";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "SYSTEM";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "ACCESSIBILITY_OVERLAY";
            default:
                return "UNKNOWN";
        }
    }
    
    /**
     * 获取根节点（需要系统权限）
     */
    private AccessibilityNodeInfo getRootNodeInfo() {
        try {
            // TODO: 通过 AccessibilityManager 获取根节点
            // 需要 SYSTEM_UID 权限
            // AccessibilityManager am = (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
            // return am.getRootInActiveWindow();
            
            // 临时返回 null（需要实际集成）
            Slog.w(TAG, "getRootNodeInfo not implemented (requires AccessibilityManager integration)");
            return null;
            
        } catch (Exception e) {
            Slog.e(TAG, "getRootNodeInfo failed", e);
            return null;
        }
    }
    
    /**
     * 获取窗口列表
     */
    private List<AccessibilityWindowInfo> getAccessibilityWindows() {
        try {
            // TODO: 通过 AccessibilityManager 获取窗口列表
            // AccessibilityManager am = (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
            // return am.getWindows();
            
            return new ArrayList<>();
            
        } catch (Exception e) {
            Slog.e(TAG, "getAccessibilityWindows failed", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("Captures: %d, Failed: %d, Success Rate: %.2f%%",
            mTotalCaptures,
            mFailedCaptures,
            mTotalCaptures > 0 ? (1.0 - (double)mFailedCaptures / mTotalCaptures) * 100 : 0);
    }
    
    // ========== 数据结构 ==========
    
    /**
     * UI 树快照
     */
    public static class UiTreeSnapshot {
        public long timestamp;
        public String source;  // "wm" (WindowManager) or "accessibility"
        public UiNode rootNode;
        
        public String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("timestamp", timestamp);
                json.put("source", source);
                json.put("root", rootNode != null ? rootNode.toJson() : null);
                return json.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }
    
    /**
     * UI 节点
     */
    public static class UiNode {
        public String nodeId;
        public String className;
        public String packageName;
        public String text;
        public String contentDescription;
        public String viewIdResourceName;
        public Rect bounds;
        
        public boolean enabled;
        public boolean clickable;
        public boolean longClickable;
        public boolean focusable;
        public boolean focused;
        public boolean selected;
        public boolean checkable;
        public boolean checked;
        public boolean scrollable;
        public boolean editable;
        public boolean password;
        public boolean visibleToUser;
        
        public List<UiNode> children;
        
        public JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("nodeId", nodeId);
                json.put("className", className);
                json.put("packageName", packageName);
                json.put("text", text);
                json.put("contentDescription", contentDescription);
                json.put("viewIdResourceName", viewIdResourceName);
                
                if (bounds != null) {
                    JSONObject boundsJson = new JSONObject();
                    boundsJson.put("left", bounds.left);
                    boundsJson.put("top", bounds.top);
                    boundsJson.put("right", bounds.right);
                    boundsJson.put("bottom", bounds.bottom);
                    json.put("bounds", boundsJson);
                }
                
                json.put("enabled", enabled);
                json.put("clickable", clickable);
                json.put("longClickable", longClickable);
                json.put("focusable", focusable);
                json.put("focused", focused);
                json.put("selected", selected);
                json.put("checkable", checkable);
                json.put("checked", checked);
                json.put("scrollable", scrollable);
                json.put("editable", editable);
                json.put("password", password);
                json.put("visibleToUser", visibleToUser);
                
                if (children != null && !children.isEmpty()) {
                    JSONArray childrenArray = new JSONArray();
                    for (UiNode child : children) {
                        childrenArray.put(child.toJson());
                    }
                    json.put("children", childrenArray);
                }
                
                return json;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }
    
    /**
     * 窗口信息
     */
    public static class WindowInfo {
        public int windowId;
        public int layer;
        public String type;
        public boolean focused;
        public boolean active;
        public Rect bounds;
    }
}
