package com.android.server.ainative;

import android.graphics.Rect;
import android.util.Slog;

import com.android.server.ainative.AccessibilityFallbackService.UiNode;
import com.android.server.ainative.AccessibilityFallbackService.UiTreeSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UI 树一致性校验器（UI Tree Consistency Validator）
 * 
 * 职责：
 * 1. 对比主通道（WindowManager）和备通道（Accessibility）捕获的 UI 树
 * 2. 检测不一致性（节点缺失/属性差异/层级错乱）
 * 3. 生成一致性报告，供质量评估和告警
 * 
 * 核心场景：
 * - 防止"看到的内容"与"实际可操作内容"不一致导致的误操作
 * - OEM 定制导致的 WindowManager hook 失效检测
 * - Accessibility API 不完整捕获的验证
 * 
 * 校验维度：
 * 1. 结构一致性：节点数量、层级深度、父子关系
 * 2. 语义一致性：className、text、contentDescription 匹配
 * 3. 交互一致性：clickable、editable、enabled 等状态
 * 4. 几何一致性：bounds 边界匹配
 * 
 * 阈值设计：
 * - 关键节点（clickable/editable）必须 100% 匹配
 * - 装饰节点（纯展示）允许 5% 差异
 * - bounds 允许 ±2dp 容差（处理像素舍入）
 * 
 * 性能要求：
 * - 对比延迟 < 50ms（1000 节点规模）
 * - 内存占用 < 5MB（深拷贝两棵树）
 * 
 * @see AccessibilityFallbackService
 * @see AgentUiTreeService
 */
public class UiTreeConsistencyValidator {
    private static final String TAG = "UiTreeConsistencyValidator";
    
    // 边界容差（dp）
    private static final int BOUNDS_TOLERANCE_DP = 2;
    
    // 文本相似度阈值（Levenshtein 距离归一化）
    private static final double TEXT_SIMILARITY_THRESHOLD = 0.85;
    
    // 关键节点类型（必须 100% 匹配）
    private static final String[] CRITICAL_CLASSES = {
        "android.widget.Button",
        "android.widget.EditText",
        "android.widget.CheckBox",
        "android.widget.RadioButton",
        "android.widget.Switch",
        "android.widget.SeekBar"
    };
    
    /**
     * 校验两棵 UI 树的一致性
     * 
     * @param primary 主通道快照（WindowManager）
     * @param fallback 备通道快照（Accessibility）
     * @return 一致性报告
     */
    public ConsistencyReport validate(UiTreeSnapshot primary, UiTreeSnapshot fallback) {
        long startNanos = System.nanoTime();
        
        ConsistencyReport report = new ConsistencyReport();
        report.primarySource = primary != null ? primary.source : "null";
        report.fallbackSource = fallback != null ? fallback.source : "null";
        report.timestamp = System.currentTimeMillis();
        
        if (primary == null || fallback == null) {
            report.consistent = false;
            report.criticalIssues.add("One or both snapshots are null");
            return report;
        }
        
        if (primary.rootNode == null || fallback.rootNode == null) {
            report.consistent = false;
            report.criticalIssues.add("One or both root nodes are null");
            return report;
        }
        
        // 1. 构建节点索引（按 className + text 匹配）
        Map<String, UiNode> primaryIndex = buildNodeIndex(primary.rootNode);
        Map<String, UiNode> fallbackIndex = buildNodeIndex(fallback.rootNode);
        
        report.primaryNodeCount = primaryIndex.size();
        report.fallbackNodeCount = fallbackIndex.size();
        
        // 2. 结构一致性检查
        validateStructure(primary.rootNode, fallback.rootNode, report);
        
        // 3. 关键节点检查
        validateCriticalNodes(primaryIndex, fallbackIndex, report);
        
        // 4. 交互能力检查
        validateInteractivity(primaryIndex, fallbackIndex, report);
        
        // 5. 几何一致性检查
        validateGeometry(primaryIndex, fallbackIndex, report);
        
        // 6. 计算总体一致性分数
        report.consistencyScore = calculateConsistencyScore(report);
        report.consistent = report.consistencyScore >= 0.95 && report.criticalIssues.isEmpty();
        
        long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
        report.validationDurationMicros = elapsedMicros;
        
        Slog.d(TAG, String.format("Consistency validation completed: score=%.2f%%, duration=%dµs",
            report.consistencyScore * 100, elapsedMicros));
        
        return report;
    }
    
    /**
     * 构建节点索引（键 = className:text:viewId）
     */
    private Map<String, UiNode> buildNodeIndex(UiNode root) {
        Map<String, UiNode> index = new HashMap<>();
        traverseAndIndex(root, index);
        return index;
    }
    
    private void traverseAndIndex(UiNode node, Map<String, UiNode> index) {
        if (node == null) return;
        
        // 生成唯一键
        String key = generateNodeKey(node);
        index.put(key, node);
        
        // 递归子节点
        if (node.children != null) {
            for (UiNode child : node.children) {
                traverseAndIndex(child, index);
            }
        }
    }
    
    private String generateNodeKey(UiNode node) {
        String className = node.className != null ? node.className : "";
        String text = node.text != null ? node.text : "";
        String viewId = node.viewIdResourceName != null ? node.viewIdResourceName : "";
        
        // 截断超长文本
        if (text.length() > 50) {
            text = text.substring(0, 50);
        }
        
        return className + ":" + text + ":" + viewId;
    }
    
    /**
     * 校验结构一致性
     */
    private void validateStructure(UiNode primary, UiNode fallback, ConsistencyReport report) {
        int primaryDepth = calculateMaxDepth(primary);
        int fallbackDepth = calculateMaxDepth(fallback);
        
        if (Math.abs(primaryDepth - fallbackDepth) > 2) {
            report.warnings.add(String.format("Depth mismatch: primary=%d, fallback=%d",
                primaryDepth, fallbackDepth));
        }
        
        int primaryChildren = countAllNodes(primary);
        int fallbackChildren = countAllNodes(fallback);
        
        double nodeDiffRatio = Math.abs(primaryChildren - fallbackChildren) / (double) primaryChildren;
        if (nodeDiffRatio > 0.1) {  // 超过 10% 差异
            report.warnings.add(String.format("Node count mismatch: primary=%d, fallback=%d (%.1f%%)",
                primaryChildren, fallbackChildren, nodeDiffRatio * 100));
        }
    }
    
    private int calculateMaxDepth(UiNode node) {
        if (node == null || node.children == null || node.children.isEmpty()) {
            return 1;
        }
        
        int maxChildDepth = 0;
        for (UiNode child : node.children) {
            maxChildDepth = Math.max(maxChildDepth, calculateMaxDepth(child));
        }
        
        return 1 + maxChildDepth;
    }
    
    private int countAllNodes(UiNode node) {
        if (node == null) return 0;
        
        int count = 1;
        if (node.children != null) {
            for (UiNode child : node.children) {
                count += countAllNodes(child);
            }
        }
        
        return count;
    }
    
    /**
     * 校验关键节点
     */
    private void validateCriticalNodes(Map<String, UiNode> primaryIndex, 
                                       Map<String, UiNode> fallbackIndex, 
                                       ConsistencyReport report) {
        int criticalMatched = 0;
        int criticalTotal = 0;
        
        for (Map.Entry<String, UiNode> entry : primaryIndex.entrySet()) {
            UiNode primaryNode = entry.getValue();
            
            if (!isCriticalNode(primaryNode)) {
                continue;
            }
            
            criticalTotal++;
            
            UiNode fallbackNode = fallbackIndex.get(entry.getKey());
            if (fallbackNode == null) {
                report.criticalIssues.add(String.format("Critical node missing: %s (text='%s')",
                    primaryNode.className, primaryNode.text));
                continue;
            }
            
            // 检查交互属性一致性
            if (primaryNode.clickable != fallbackNode.clickable ||
                primaryNode.editable != fallbackNode.editable ||
                primaryNode.enabled != fallbackNode.enabled) {
                report.criticalIssues.add(String.format("Critical node state mismatch: %s",
                    primaryNode.viewIdResourceName));
            } else {
                criticalMatched++;
            }
        }
        
        report.criticalNodeMatchRate = criticalTotal > 0 ? 
            (double) criticalMatched / criticalTotal : 1.0;
    }
    
    private boolean isCriticalNode(UiNode node) {
        if (node.className == null) return false;
        
        for (String criticalClass : CRITICAL_CLASSES) {
            if (node.className.equals(criticalClass)) {
                return true;
            }
        }
        
        // 可点击/可编辑的节点也视为关键节点
        return node.clickable || node.editable;
    }
    
    /**
     * 校验交互能力
     */
    private void validateInteractivity(Map<String, UiNode> primaryIndex, 
                                       Map<String, UiNode> fallbackIndex, 
                                       ConsistencyReport report) {
        int interactiveMatched = 0;
        int interactiveTotal = 0;
        
        for (Map.Entry<String, UiNode> entry : primaryIndex.entrySet()) {
            UiNode primaryNode = entry.getValue();
            
            if (!isInteractive(primaryNode)) {
                continue;
            }
            
            interactiveTotal++;
            
            UiNode fallbackNode = fallbackIndex.get(entry.getKey());
            if (fallbackNode != null && isInteractive(fallbackNode)) {
                interactiveMatched++;
            }
        }
        
        report.interactiveNodeMatchRate = interactiveTotal > 0 ? 
            (double) interactiveMatched / interactiveTotal : 1.0;
    }
    
    private boolean isInteractive(UiNode node) {
        return node.clickable || node.longClickable || node.editable || 
               node.checkable || node.focusable;
    }
    
    /**
     * 校验几何一致性
     */
    private void validateGeometry(Map<String, UiNode> primaryIndex, 
                                  Map<String, UiNode> fallbackIndex, 
                                  ConsistencyReport report) {
        int geometryMatched = 0;
        int geometryTotal = 0;
        
        for (Map.Entry<String, UiNode> entry : primaryIndex.entrySet()) {
            UiNode primaryNode = entry.getValue();
            
            if (primaryNode.bounds == null) {
                continue;
            }
            
            geometryTotal++;
            
            UiNode fallbackNode = fallbackIndex.get(entry.getKey());
            if (fallbackNode != null && fallbackNode.bounds != null) {
                if (boundsMatch(primaryNode.bounds, fallbackNode.bounds)) {
                    geometryMatched++;
                } else {
                    report.warnings.add(String.format("Bounds mismatch: %s primary=%s fallback=%s",
                        primaryNode.viewIdResourceName,
                        primaryNode.bounds.toShortString(),
                        fallbackNode.bounds.toShortString()));
                }
            }
        }
        
        report.geometryMatchRate = geometryTotal > 0 ? 
            (double) geometryMatched / geometryTotal : 1.0;
    }
    
    private boolean boundsMatch(Rect r1, Rect r2) {
        int tolerance = BOUNDS_TOLERANCE_DP;
        
        return Math.abs(r1.left - r2.left) <= tolerance &&
               Math.abs(r1.top - r2.top) <= tolerance &&
               Math.abs(r1.right - r2.right) <= tolerance &&
               Math.abs(r1.bottom - r2.bottom) <= tolerance;
    }
    
    /**
     * 计算总体一致性分数
     */
    private double calculateConsistencyScore(ConsistencyReport report) {
        // 权重分配
        double criticalWeight = 0.5;
        double interactiveWeight = 0.3;
        double geometryWeight = 0.2;
        
        // 有致命问题直接返回 0
        if (!report.criticalIssues.isEmpty()) {
            return 0.0;
        }
        
        double score = criticalWeight * report.criticalNodeMatchRate +
                       interactiveWeight * report.interactiveNodeMatchRate +
                       geometryWeight * report.geometryMatchRate;
        
        return Math.max(0.0, Math.min(1.0, score));
    }
    
    // ========== 数据结构 ==========
    
    /**
     * 一致性报告
     */
    public static class ConsistencyReport {
        public long timestamp;
        public String primarySource;
        public String fallbackSource;
        
        public int primaryNodeCount;
        public int fallbackNodeCount;
        
        public boolean consistent;
        public double consistencyScore;
        
        public double criticalNodeMatchRate;
        public double interactiveNodeMatchRate;
        public double geometryMatchRate;
        
        public List<String> criticalIssues = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        
        public long validationDurationMicros;
        
        public String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("timestamp", timestamp);
                json.put("primarySource", primarySource);
                json.put("fallbackSource", fallbackSource);
                json.put("primaryNodeCount", primaryNodeCount);
                json.put("fallbackNodeCount", fallbackNodeCount);
                json.put("consistent", consistent);
                json.put("consistencyScore", consistencyScore);
                json.put("criticalNodeMatchRate", criticalNodeMatchRate);
                json.put("interactiveNodeMatchRate", interactiveNodeMatchRate);
                json.put("geometryMatchRate", geometryMatchRate);
                
                JSONArray criticalArray = new JSONArray();
                for (String issue : criticalIssues) {
                    criticalArray.put(issue);
                }
                json.put("criticalIssues", criticalArray);
                
                JSONArray warningsArray = new JSONArray();
                for (String warning : warnings) {
                    warningsArray.put(warning);
                }
                json.put("warnings", warningsArray);
                
                json.put("validationDurationMicros", validationDurationMicros);
                
                return json.toString(2);
            } catch (Exception e) {
                return "{}";
            }
        }
        
        public String toShortString() {
            return String.format("Consistent=%b, Score=%.2f%%, Critical=%d, Warnings=%d",
                consistent, consistencyScore * 100, criticalIssues.size(), warnings.size());
        }
    }
}
