package com.android.server.ainative;

import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.ainative.perception.IAgentUiTreeListener;
import com.ainative.perception.IAgentUiTreeService;
import com.ainative.perception.UiNode;
import com.ainative.perception.UiSnapshot;
import com.ainative.perception.UiTreeDiff;
import com.ainative.perception.UiTreeRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent UI 树服务
 * 
 * 职责：
 * 1. WindowManager/View 层级合成
 * 2. 快照 + 增量更新
 * 3. PII 掩码
 * 4. FLAG_SECURE 区域标记
 * 
 * 对应任务：A2.1
 * 前置依赖：A1.3（AIDL 骨架）
 * 草案来源：D1
 * 
 * @author AI Native OS Team
 * @since A2 (读-写-界闭环)
 */
public class AgentUiTreeService extends IAgentUiTreeService.Stub {
    
    /**
     * 单点鉴权（WBS 1.3）：UI 树包含任意前台界面内容，属隐私敏感面。
     * 本接口全部事务（含未来新增方法）都要求 BIND_UI_TREE_SERVICE（signature|privileged）。
     */
    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws android.os.RemoteException {
        AgentCallerAuth.enforceTransaction(mContext, AgentCallerAuth.PERM_BIND_UI_TREE,
                "AgentUiTreeService", code);
        return super.onTransact(code, data, reply, flags);
    }
    private static final String TAG = "AgentUiTreeService";
    
    // 状态常量
    private static final int STATUS_OK = 0;
    private static final int STATUS_PERMISSION_DENIED = 1;
    private static final int STATUS_SECURE_WINDOW = 2;
    private static final int STATUS_INTERNAL_ERROR = -1;
    
    // PII 掩码模式
    private static final int MASK_MODE_NONE = 0;        // 无掩码
    private static final int MASK_MODE_PARTIAL = 1;     // 部分掩码（保留长度）
    private static final int MASK_MODE_FULL = 2;        // 完全掩码
    
    // 上下文
    private final Context mContext;
    private final WindowManager mWindowManager;
    
    // 监听器管理
    private final RemoteCallbackList<IAgentUiTreeListener> mListeners;
    
    // UI 树缓存（用于增量更新）
    private final ConcurrentHashMap<String, UiTreeCache> mTreeCache;
    
    // 安全区域追踪
    private final Map<Integer, SecureRegion> mSecureRegions;
    
    // 快照序列号
    private long mSnapshotSequence = 0;
    
    // 双通道支持（A2.4）
    private AccessibilityFallbackService mFallbackService;
    private UiTreeConsistencyValidator mConsistencyValidator;
    private boolean mUseFallbackMode = false;
    private int mPrimaryFailureCount = 0;
    private static final int FALLBACK_THRESHOLD = 3;
    
    // 审计埋点（A5.3）
    private AuditInstrumentation mAuditInstrumentation;
    
    /**
     * 构造函数
     */
    public AgentUiTreeService(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mListeners = new RemoteCallbackList<>();
        mTreeCache = new ConcurrentHashMap<>();
        mSecureRegions = new HashMap<>();
        
        // 初始化双通道组件（A2.4）
        mFallbackService = new AccessibilityFallbackService(context);
        mConsistencyValidator = new UiTreeConsistencyValidator();
        
        // 初始化审计埋点（A5.3）
        mAuditInstrumentation = new AuditInstrumentation(context);
        
        Slog.i(TAG, "AgentUiTreeService initialized");
    }
    
    /**
     * 捕获 UI 树快照
     * 
     * 流程：
     * 1. 权限检查（READ_UI_TREE）
     * 2. 遍历 WindowManager 窗口层级
     * 3. 构建 View 树结构
     * 4. PII 掩码处理
     * 5. FLAG_SECURE 区域标记
     * 6. 增量 diff（如果有缓存）
     * 
     * @param request 捕获请求（包含 mask 配置、增量模式等）
     * @return UiSnapshot 快照对象
     */
    @Override
    public UiSnapshot captureUiTree(UiTreeRequest request) {
        try {
            // 1. 权限检查
            if (!checkPermission()) {
                return createErrorSnapshot(STATUS_PERMISSION_DENIED, "Permission denied");
            }
            
            long startTime = System.nanoTime();
            
            // 2. 选择捕获通道（主通道 vs 回退通道）
            UiSnapshot snapshot = null;
            
            if (mUseFallbackMode) {
                // 回退模式：直接使用 Accessibility
                snapshot = captureViaFallback(request, startTime);
            } else {
                // 主通道模式：尝试 WindowManager 捕获
                try {
                    snapshot = captureViaPrimary(request, startTime);
                    mPrimaryFailureCount = 0;  // 成功，重置失败计数
                } catch (Exception e) {
                    Slog.w(TAG, "Primary channel failed, attempting fallback: " + e.getMessage());
                    
                    // 主通道失败，尝试回退
                    snapshot = captureViaFallback(request, startTime);
                    
                    mPrimaryFailureCount++;
                    if (mPrimaryFailureCount >= FALLBACK_THRESHOLD) {
                        Slog.e(TAG, "Primary channel failed " + mPrimaryFailureCount + 
                            " times, switching to fallback mode permanently");
                        mUseFallbackMode = true;
                    }
                }
            }
            
            // 3. 一致性校验（如果启用）
            if (request.enableConsistencyCheck && !mUseFallbackMode) {
                performConsistencyCheck(snapshot);
            }
            
            // 4. 审计埋点（A5.3）
            if (snapshot != null && snapshot.statusCode == STATUS_OK) {
                String snapshotId = "snap_" + snapshot.sequenceId;
                String summary = String.format("捕获 UI 树 seq=%d source=%s nodes=%d latency=%dms",
                    snapshot.sequenceId,
                    snapshot.captureSource,
                    countNodes(snapshot.rootNode),
                    snapshot.captureLatencyNanos / 1000000);
                
                mAuditInstrumentation.recordPerceptionRead(
                    "ui_tree",
                    request.agentId,
                    request.intentId,
                    summary,
                    snapshotId
                );
            }
            
            return snapshot;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to capture UI tree", e);
            return createErrorSnapshot(STATUS_INTERNAL_ERROR, e.getMessage());
        }
    }
    
    /**
     * 通过主通道捕获（WindowManager）
     */
    private UiSnapshot captureViaPrimary(UiTreeRequest request, long startTime) throws Exception {
        // 原主通道逻辑
        List<WindowNode> windows = captureWindowHierarchy();
        UiTreeNode rootNode = buildUiTree(windows, request);
        
        if (request.maskPii) {
            applyPiiMask(rootNode, request.maskMode);
        }
        
        markSecureRegions(rootNode);
        
        UiSnapshot snapshot = new UiSnapshot();
        snapshot.sequenceId = ++mSnapshotSequence;
        snapshot.timestampNanos = System.nanoTime();
        snapshot.rootNode = toUiNode(rootNode);
        snapshot.captureLatencyNanos = snapshot.timestampNanos - startTime;
        snapshot.captureSource = "wm";  // WindowManager
        
        if (request.incrementalMode && request.previousSequenceId > 0) {
            UiTreeCache cache = mTreeCache.get(request.sessionId);
            if (cache != null && cache.sequenceId == request.previousSequenceId) {
                snapshot.incrementalDiff = computeDiff(cache.rootNode, rootNode);
                snapshot.isIncremental = true;
            }
        }
        
        if (request.sessionId != null) {
            mTreeCache.put(request.sessionId, new UiTreeCache(
                snapshot.sequenceId,
                rootNode,
                System.nanoTime()
            ));
        }
        
        Slog.d(TAG, "Captured UI tree via primary: seq=" + snapshot.sequenceId 
            + ", nodes=" + countNodes(rootNode)
            + ", latency=" + (snapshot.captureLatencyNanos / 1000000) + "ms");
        
        return snapshot;
    }
    
    /**
     * 通过回退通道捕获（Accessibility）
     */
    private UiSnapshot captureViaFallback(UiTreeRequest request, long startTime) throws Exception {
        AccessibilityFallbackService.UiTreeSnapshot fallbackSnapshot = 
            mFallbackService.captureUiTree();
        
        if (fallbackSnapshot == null) {
            throw new Exception("Fallback channel also failed");
        }
        
        // 转换为标准 UiSnapshot 格式
        UiSnapshot snapshot = new UiSnapshot();
        snapshot.sequenceId = ++mSnapshotSequence;
        snapshot.timestampNanos = System.nanoTime();
        snapshot.captureLatencyNanos = snapshot.timestampNanos - startTime;
        snapshot.captureSource = "accessibility";  // Accessibility 回退
        
        // 转换节点（简化版，实际需完整映射）
        snapshot.rootNode = toUiNode(convertFallbackNode(fallbackSnapshot.rootNode));
        
        Slog.i(TAG, "Captured UI tree via fallback: seq=" + snapshot.sequenceId 
            + ", latency=" + (snapshot.captureLatencyNanos / 1000000) + "ms");
        
        return snapshot;
    }
    
    /**
     * 执行一致性校验
     */
    private void performConsistencyCheck(UiSnapshot primarySnapshot) {
        try {
            // 同时捕获回退通道
            AccessibilityFallbackService.UiTreeSnapshot fallbackSnapshot = 
                mFallbackService.captureUiTree();
            
            if (fallbackSnapshot == null) {
                Slog.w(TAG, "Fallback capture failed, skipping consistency check");
                return;
            }
            
            // 转换为统一格式
            AccessibilityFallbackService.UiTreeSnapshot primaryConverted = 
                new AccessibilityFallbackService.UiTreeSnapshot();
            primaryConverted.timestamp = primarySnapshot.timestampNanos / 1000000;
            primaryConverted.source = "wm";
            primaryConverted.rootNode = convertToFallbackFormat(primarySnapshot.rootNode);
            
            // 执行一致性校验
            UiTreeConsistencyValidator.ConsistencyReport report = 
                mConsistencyValidator.validate(primaryConverted, fallbackSnapshot);
            
            Slog.i(TAG, "Consistency check: " + report.toShortString());
            
            // 如果不一致且严重，记录告警
            if (!report.consistent) {
                Slog.w(TAG, "UI tree inconsistency detected:\n" + report.toJson());
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Consistency check failed", e);
        }
    }
    
    /**
     * 转换 Fallback 节点到标准格式（简化版）
     */
    private UiTreeNode convertFallbackNode(AccessibilityFallbackService.UiNode fallbackNode) {
        if (fallbackNode == null) return null;
        
        UiTreeNode node = new UiTreeNode();
        node.nodeId = fallbackNode.nodeId;
        node.nodeType = fallbackNode.className;
        node.packageName = fallbackNode.packageName;
        node.text = fallbackNode.text;
        node.contentDescription = fallbackNode.contentDescription;
        node.resourceId = fallbackNode.viewIdResourceName;
        node.bounds = toIntArray(fallbackNode.bounds);
        node.isClickable = fallbackNode.clickable;
        node.isFocused = fallbackNode.focused;
        node.enabled = fallbackNode.enabled;
        
        // 递归转换子节点
        if (fallbackNode.children != null) {
            node.children = new ArrayList<>();
            for (AccessibilityFallbackService.UiNode child : fallbackNode.children) {
                UiTreeNode converted = convertFallbackNode(child);
                if (converted != null) {
                    node.children.add(converted);
                }
            }
        }
        
        return node;
    }
    
    /**
     * 转换标准节点到 Fallback 格式（用于一致性对比）
     */
    private AccessibilityFallbackService.UiNode convertToFallbackFormat(UiNode node) {
        if (node == null) return null;
        
        AccessibilityFallbackService.UiNode fallbackNode = 
            new AccessibilityFallbackService.UiNode();
        fallbackNode.nodeId = node.nodeId;
        fallbackNode.className = node.className;
        fallbackNode.packageName = node.packageName;
        fallbackNode.text = node.text;
        fallbackNode.contentDescription = node.contentDesc;
        fallbackNode.viewIdResourceName = node.resourceId;
        fallbackNode.bounds = node.bounds;
        fallbackNode.clickable = node.clickable;
        fallbackNode.focused = node.focused;
        fallbackNode.enabled = node.enabled;
        
        // 递归转换子节点
        if (node.children != null) {
            fallbackNode.children = new ArrayList<>();
            for (UiNode child : node.children) {
                AccessibilityFallbackService.UiNode converted = convertToFallbackFormat(child);
                if (converted != null) {
                    fallbackNode.children.add(converted);
                }
            }
        }
        
        return fallbackNode;
    }
    
    /**
     * 内部节点树转换为 AIDL 契约节点树（UiNode 为对外快照格式）
     */
    private static UiNode toUiNode(UiTreeNode node) {
        if (node == null) return null;
        
        UiNode out = new UiNode();
        out.nodeId = node.nodeId;
        out.className = node.nodeType;
        out.text = node.text;
        out.contentDesc = node.contentDescription;
        out.resourceId = node.resourceId;
        out.bounds = toRect(node.bounds);
        out.clickable = node.isClickable;
        out.enabled = node.enabled;
        out.focused = node.isFocused;
        out.visible = node.isVisible;
        out.flagSecure = node.isFlagSecure;
        out.zOrder = node.zOrder;
        out.piiMasked = node.isPiiMasked;
        out.secureReason = node.secureReason;
        out.packageName = node.packageName;
        
        if (node.children != null) {
            out.children = new ArrayList<>(node.children.size());
            for (UiTreeNode child : node.children) {
                UiNode converted = toUiNode(child);
                if (converted != null) {
                    out.children.add(converted);
                }
            }
        }
        
        return out;
    }
    
    /**
     * [left, top, right, bottom] -> Rect
     */
    private static Rect toRect(int[] bounds) {
        if (bounds == null || bounds.length < 4) return null;
        return new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
    }
    
    /**
     * Rect -> [left, top, right, bottom]
     */
    private static int[] toIntArray(Rect rect) {
        if (rect == null) return null;
        return new int[]{rect.left, rect.top, rect.right, rect.bottom};
    }
    
    
    /**
     * 订阅 UI 变更事件
     * 
     * 监听：
     * - 窗口添加/移除
     * - View 层级变化
     * - 内容文本变更
     * - 焦点变化
     */
    @Override
    public void subscribeUiChanges(IAgentUiTreeListener listener) {
        if (listener == null) {
            Slog.w(TAG, "Null listener, ignoring subscription");
            return;
        }
        
        if (!checkPermission()) {
            Slog.w(TAG, "Permission denied for UI change subscription");
            return;
        }
        
        mListeners.register(listener);
        Slog.i(TAG, "Subscribed UI change listener, total=" + mListeners.getRegisteredCallbackCount());
    }
    
    /**
     * 取消订阅 UI 变更事件
     */
    @Override
    public void unsubscribeUiChanges(IAgentUiTreeListener listener) {
        if (listener == null) {
            return;
        }
        
        mListeners.unregister(listener);
        Slog.i(TAG, "Unsubscribed UI change listener, remaining=" + mListeners.getRegisteredCallbackCount());
    }
    
    /**
     * 根据节点 ID 查询单个 UI 节点
     * 
     * @param nodeId 节点唯一标识
     * @return 节点信息，如果不存在返回 null
     */
    @Override
    public com.ainative.perception.UiNode getNodeById(String nodeId) {
        // TODO: 实现节点查询逻辑
        // 当前返回 null，需要在实际实现中从缓存的 UI 树中查找节点
        Slog.w(TAG, "getNodeById not yet fully implemented: " + nodeId);
        return null;
    }
    
    /**
     * 获取当前 UI 树序列号
     * 
     * 用于检测 UI 树是否已过时（stale detection）
     * @return 序列号，每次 UI 树变更时递增
     */
    @Override
    public long getCurrentSequence() {
        return mSnapshotSequence;
    }
    
    /**
     * 捕获窗口层级
     * 
     * 通过 WindowManager 遍历所有可见窗口
     */
    private List<WindowNode> captureWindowHierarchy() {
        List<WindowNode> windows = new ArrayList<>();
        
        // TODO: 实际实现需要反射访问 WindowManagerService
        // 这里提供骨架示例
        
        /*
        WindowManagerService wms = LocalServices.getService(WindowManagerService.class);
        synchronized (wms.mGlobalLock) {
            wms.mRoot.forAllWindows(window -> {
                if (window.isVisible()) {
                    WindowNode node = new WindowNode();
                    node.windowId = window.mWindowId;
                    node.title = window.getWindowTag().toString();
                    node.packageName = window.getOwningPackage();
                    node.bounds = window.getFrame();
                    node.isFlagSecure = (window.getAttrs().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
                    node.zOrder = window.mLayer;
                    windows.add(node);
                }
            }, false);
        }
        */
        
        Slog.d(TAG, "Captured " + windows.size() + " windows");
        return windows;
    }
    
    /**
     * 构建 UI 树
     * 
     * 遍历 View 层级，构建树结构
     */
    private UiTreeNode buildUiTree(List<WindowNode> windows, UiTreeRequest request) {
        UiTreeNode root = new UiTreeNode();
        root.nodeId = "root";
        root.nodeType = "screen";
        root.enabled = true;
        root.isVisible = true;
        root.children = new ArrayList<>();
        
        for (WindowNode window : windows) {
            UiTreeNode windowNode = buildWindowNode(window, request);
            root.children.add(windowNode);
        }
        
        return root;
    }
    
    /**
     * 构建窗口节点
     */
    private UiTreeNode buildWindowNode(WindowNode window, UiTreeRequest request) {
        UiTreeNode node = new UiTreeNode();
        node.nodeId = "window_" + window.windowId;
        node.nodeType = "window";
        node.packageName = window.packageName;
        node.bounds = window.bounds;
        node.isFlagSecure = window.isFlagSecure;
        node.zOrder = window.zOrder;
        node.enabled = true;
        node.isVisible = true;
        node.children = new ArrayList<>();
        
        // 递归构建 View 树
        if (window.rootView != null && !window.isFlagSecure) {
            buildViewTree(window.rootView, node, request);
        } else if (window.isFlagSecure) {
            // FLAG_SECURE 窗口仅保留边界信息
            node.secureReason = "FLAG_SECURE";
        }
        
        return node;
    }
    
    /**
     * 递归构建 View 树
     */
    private void buildViewTree(View view, UiTreeNode parentNode, UiTreeRequest request) {
        UiTreeNode node = new UiTreeNode();
        node.nodeId = "view_" + view.hashCode();
        node.nodeType = view.getClass().getSimpleName();
        node.bounds = new int[]{view.getLeft(), view.getTop(), view.getRight(), view.getBottom()};
        node.isVisible = view.getVisibility() == View.VISIBLE;
        node.isClickable = view.isClickable();
        node.isFocused = view.isFocused();
        node.enabled = view.isEnabled();
        
        // 提取文本内容
        if (view instanceof android.widget.TextView) {
            android.widget.TextView textView = (android.widget.TextView) view;
            CharSequence text = textView.getText();
            if (text != null) {
                node.text = text.toString();
                node.textLength = text.length();
            }
        }
        
        // 提取内容描述
        CharSequence contentDesc = view.getContentDescription();
        if (contentDesc != null) {
            node.contentDescription = contentDesc.toString();
        }
        
        // 提取资源 ID
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                node.resourceId = view.getResources().getResourceName(id);
            } catch (Exception e) {
                node.resourceId = "0x" + Integer.toHexString(id);
            }
        }
        
        parentNode.children.add(node);
        
        // 递归子 View
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                buildViewTree(child, node, request);
            }
        }
    }
    
    /**
     * 应用 PII 掩码
     * 
     * 策略：
     * - 电话号码、邮箱、身份证号：完全掩码
     * - 姓名、地址：部分掩码（保留首尾）
     * - 密码框：完全掩码
     */
    private void applyPiiMask(UiTreeNode node, int maskMode) {
        if (node == null) {
            return;
        }
        
        // 掩码文本内容
        if (node.text != null && !node.text.isEmpty()) {
            node.text = maskText(node.text, maskMode);
            node.isPiiMasked = true;
        }
        
        // 掩码内容描述
        if (node.contentDescription != null && !node.contentDescription.isEmpty()) {
            node.contentDescription = maskText(node.contentDescription, maskMode);
        }
        
        // 递归子节点
        if (node.children != null) {
            for (UiTreeNode child : node.children) {
                applyPiiMask(child, maskMode);
            }
        }
    }
    
    /**
     * 掩码文本
     */
    private String maskText(String text, int maskMode) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // 电话号码检测
        if (text.matches("\\d{11}")) {
            return text.substring(0, 3) + "****" + text.substring(7);
        }
        
        // 邮箱检测
        if (text.contains("@") && text.contains(".")) {
            int atIndex = text.indexOf("@");
            return text.charAt(0) + "***@" + text.substring(atIndex + 1);
        }
        
        // 身份证号检测
        if (text.matches("\\d{17}[0-9Xx]")) {
            return text.substring(0, 6) + "********" + text.substring(14);
        }
        
        // 默认部分掩码（保留长度信息）
        if (maskMode == MASK_MODE_FULL) {
            return "[MASKED:" + text.length() + "]";
        } else if (maskMode == MASK_MODE_PARTIAL && text.length() > 4) {
            return text.substring(0, 2) + "***" + text.substring(text.length() - 2);
        }
        
        return text;
    }
    
    /**
     * 标记安全区域
     * 
     * 检测 FLAG_SECURE 窗口并标记为安全区域
     */
    private void markSecureRegions(UiTreeNode node) {
        if (node == null) {
            return;
        }
        
        if (node.isFlagSecure) {
            SecureRegion region = new SecureRegion();
            region.bounds = node.bounds;
            region.reason = node.secureReason != null ? node.secureReason : "FLAG_SECURE";
            region.packageName = node.packageName;
            
            synchronized (mSecureRegions) {
                mSecureRegions.put(node.hashCode(), region);
            }
        }
        
        // 递归检查子节点
        if (node.children != null) {
            for (UiTreeNode child : node.children) {
                markSecureRegions(child);
            }
        }
    }
    
    /**
     * 计算增量 diff
     * 
     * 比较两棵树，生成增量更新
     */
    private UiTreeDiff computeDiff(UiTreeNode oldTree, UiTreeNode newTree) {
        UiTreeDiff diff = new UiTreeDiff();
        diff.addedNodes = new ArrayList<>();
        diff.removedNodes = new ArrayList<>();
        diff.changedNodes = new ArrayList<>();
        
        // TODO: 实现树 diff 算法
        // 简化版：直接返回全量
        
        return diff;
    }
    /**
     * 统计节点数量（内部树）
     */
    private int countNodes(UiTreeNode node) {
        if (node == null) {
            return 0;
        }
        
        int count = 1;
        if (node.children != null) {
            for (UiTreeNode child : node.children) {
                count += countNodes(child);
            }
        }
        
        return count;
    }
    
    /**
     * 统计节点数量（AIDL 契约树）
     */
    private int countNodes(UiNode node) {
        if (node == null) {
            return 0;
        }
        
        int count = 1;
        if (node.children != null) {
            for (UiNode child : node.children) {
                count += countNodes(child);
            }
        }
        
        return count;
    }
    
    /**
     * 权限检查
     */
    private boolean checkPermission() {
        // WBS 1.3：真实校验（此前为注释掉的占位实现 + return true）
        if (AgentCallerAuth.isSystemCaller()) {
            return true;
        }
        return AgentCallerAuth.holdsPermission(mContext, AgentCallerAuth.PERM_READ_UI_TREE);
    }
    
    /**
     * 创建错误快照
     */
    private UiSnapshot createErrorSnapshot(int statusCode, String message) {
        UiSnapshot snapshot = new UiSnapshot();
        snapshot.sequenceId = -1;
        snapshot.timestampNanos = System.nanoTime();
        snapshot.statusCode = statusCode;
        snapshot.errorMessage = message;
        return snapshot;
    }
    
    /**
     * 通知 UI 变更
     */
    private void notifyUiChanged(UiSnapshot snapshot) {
        int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mListeners.getBroadcastItem(i).onUiTreeChanged(snapshot);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify listener", e);
            }
        }
        mListeners.finishBroadcast();
    }
    
    /**
     * 清理过期缓存
     */
    public void cleanupExpiredCache(long maxAgeMs) {
        long now = System.nanoTime();
        mTreeCache.entrySet().removeIf(entry -> {
            long ageMs = (now - entry.getValue().timestampNanos) / 1000000;
            return ageMs > maxAgeMs;
        });
    }
    
    // ===== 内部数据结构 =====
    
    /**
     * 窗口节点
     */
    private static class WindowNode {
        int windowId;
        String title;
        String packageName;
        int[] bounds;  // [left, top, right, bottom]
        boolean isFlagSecure;
        int zOrder;
        View rootView;
    }
    
    /**
     * UI 树节点
     */
    public static class UiTreeNode {
        String nodeId;
        String nodeType;
        String packageName;
        int[] bounds;
        boolean isVisible;
        boolean isClickable;
        boolean isFocused;
        boolean enabled;
        boolean isFlagSecure;
        int zOrder;
        String text;
        int textLength;
        String contentDescription;
        String resourceId;
        boolean isPiiMasked;
        String secureReason;
        List<UiTreeNode> children;
    }
    
    /**
     * UI 树缓存
     */
    private static class UiTreeCache {
        long sequenceId;
        UiTreeNode rootNode;
        long timestampNanos;
        
        UiTreeCache(long sequenceId, UiTreeNode rootNode, long timestampNanos) {
            this.sequenceId = sequenceId;
            this.rootNode = rootNode;
            this.timestampNanos = timestampNanos;
        }
    }
    
    /**
     * 安全区域
     */
    private static class SecureRegion {
        int[] bounds;
        String reason;
        String packageName;
    }
}
