package com.ainative.perception.ui

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * A2.1 UI树服务 - 节点稳定ID生成器
 * 
 * 职责：
 * 1. 为UI节点生成稳定的ID（会话内保持稳定）
 * 2. 基于节点特征生成哈希
 * 3. 支持增量变更追踪
 * 4. 处理节点重用和更新
 * 
 * 设计原则：
 * - ID在会话内保持稳定（相同位置/特征的节点使用相同ID）
 * - 基于多维度特征计算哈希（路径、资源ID、类名、文本）
 * - 支持节点映射缓存
 */
class NodeIdGenerator {
    
    companion object {
        private const val TAG = "NodeIdGenerator"
        
        // ID前缀
        private const val NODE_ID_PREFIX = "n"
        
        // 哈希算法
        private const val HASH_ALGORITHM = "SHA-256"
    }
    
    // 全局快照ID计数器
    private val snapshotIdCounter = AtomicLong(0)
    
    // 节点ID映射（特征哈希 -> 节点ID）
    private val nodeIdCache = mutableMapOf<String, String>()
    
    // 节点计数器（用于同哈希节点区分）
    private val nodeCounter = AtomicLong(0)
    
    /**
     * 生成快照ID
     */
    fun generateSnapshotId(): Long {
        return snapshotIdCounter.incrementAndGet()
    }
    
    /**
     * 为View生成节点ID
     */
    fun generateNodeId(view: View, parentPath: String = ""): String {
        val features = extractViewFeatures(view, parentPath)
        val featureHash = computeHash(features)
        
        // 检查缓存
        return nodeIdCache.getOrPut(featureHash) {
            val count = nodeCounter.incrementAndGet()
            "${NODE_ID_PREFIX}_${featureHash.take(8)}_$count"
        }
    }
    
    /**
     * 为AccessibilityNodeInfo生成节点ID
     */
    fun generateNodeId(node: AccessibilityNodeInfo, parentPath: String = ""): String {
        val features = extractNodeInfoFeatures(node, parentPath)
        val featureHash = computeHash(features)
        
        return nodeIdCache.getOrPut(featureHash) {
            val count = nodeCounter.incrementAndGet()
            "${NODE_ID_PREFIX}_${featureHash.take(8)}_$count"
        }
    }
    
    /**
     * 提取View特征
     */
    private fun extractViewFeatures(view: View, parentPath: String): String {
        val sb = StringBuilder()
        
        // 1. 路径（父节点路径）
        sb.append(parentPath)
        sb.append("/")
        
        // 2. 类名
        sb.append(view.javaClass.simpleName)
        sb.append("|")
        
        // 3. 资源ID
        if (view.id != View.NO_ID) {
            try {
                val resourceName = view.resources.getResourceName(view.id)
                sb.append(resourceName)
            } catch (e: Exception) {
                sb.append(view.id)
            }
        }
        sb.append("|")
        
        // 4. 在父容器中的索引
        val parent = view.parent
        if (parent is android.view.ViewGroup) {
            val index = parent.indexOfChild(view)
            sb.append(index)
        }
        sb.append("|")
        
        // 5. 边界（相对位置）
        sb.append("${view.left},${view.top},${view.right},${view.bottom}")
        
        return sb.toString()
    }
    
    /**
     * 提取AccessibilityNodeInfo特征
     */
    private fun extractNodeInfoFeatures(node: AccessibilityNodeInfo, parentPath: String): String {
        val sb = StringBuilder()
        
        // 1. 路径
        sb.append(parentPath)
        sb.append("/")
        
        // 2. 类名
        node.className?.let { sb.append(it) }
        sb.append("|")
        
        // 3. 视图ID
        node.viewIdResourceName?.let { sb.append(it) }
        sb.append("|")
        
        // 4. 包名
        node.packageName?.let { sb.append(it) }
        sb.append("|")
        
        // 5. 内容描述
        node.contentDescription?.let { 
            sb.append(it.toString().take(50))  // 限制长度
        }
        sb.append("|")
        
        // 6. 文本
        node.text?.let { 
            sb.append(it.toString().take(50))  // 限制长度
        }
        
        return sb.toString()
    }
    
    /**
     * 计算特征哈希
     */
    private fun computeHash(features: String): String {
        return try {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            val hashBytes = digest.digest(features.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // 降级：使用简单哈希
            features.hashCode().toString(16)
        }
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        nodeIdCache.clear()
        nodeCounter.set(0)
    }
    
    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Int {
        return nodeIdCache.size
    }
    
    /**
     * 重置快照ID计数器
     */
    fun resetSnapshotCounter() {
        snapshotIdCounter.set(0)
    }
}

/**
 * 节点路径构建器
 * 
 * 用于构建节点在树中的路径，便于生成稳定ID
 */
class NodePathBuilder {
    
    private val pathSegments = mutableListOf<String>()
    
    /**
     * 添加路径段
     */
    fun append(segment: String): NodePathBuilder {
        pathSegments.add(segment)
        return this
    }
    
    /**
     * 添加索引
     */
    fun appendIndex(index: Int): NodePathBuilder {
        pathSegments.add("[$index]")
        return this
    }
    
    /**
     * 添加资源ID
     */
    fun appendResourceId(resourceId: String): NodePathBuilder {
        pathSegments.add("#$resourceId")
        return this
    }
    
    /**
     * 构建路径字符串
     */
    fun build(): String {
        return pathSegments.joinToString("/")
    }
    
    /**
     * 清空路径
     */
    fun clear() {
        pathSegments.clear()
    }
    
    /**
     * 复制路径构建器
     */
    fun copy(): NodePathBuilder {
        val copy = NodePathBuilder()
        copy.pathSegments.addAll(this.pathSegments)
        return copy
    }
}

/**
 * 节点特征提取器
 * 
 * 提取节点的关键特征用于ID生成和变化检测
 */
object NodeFeatureExtractor {
    
    /**
     * 提取节点签名（用于变化检测）
     */
    fun extractSignature(node: UiNode): String {
        return buildString {
            append(node.className ?: "")
            append("|")
            append(node.resourceId ?: "")
            append("|")
            append(node.text ?: "")
            append("|")
            append(node.contentDescription ?: "")
            append("|")
            append(node.clickable)
            append("|")
            append(node.focused)
            append("|")
            append(node.checked)
            append("|")
            append(node.enabled)
        }
    }
    
    /**
     * 比较两个节点是否实质相同
     */
    fun areNodesEqual(node1: UiNode, node2: UiNode): Boolean {
        return extractSignature(node1) == extractSignature(node2)
    }
    
    /**
     * 检测节点变化类型
     */
    fun detectChangeType(oldNode: UiNode?, newNode: UiNode?): ChangeType {
        return when {
            oldNode == null && newNode != null -> ChangeType.ADDED
            oldNode != null && newNode == null -> ChangeType.REMOVED
            oldNode != null && newNode != null -> {
                if (areNodesEqual(oldNode, newNode)) {
                    ChangeType.UNCHANGED
                } else {
                    ChangeType.UPDATED
                }
            }
            else -> ChangeType.UNCHANGED
        }
    }
}

/**
 * 变化类型枚举
 */
enum class ChangeType {
    ADDED,      // 新增
    UPDATED,    // 更新
    REMOVED,    // 删除
    UNCHANGED   // 未变化
}

/**
 * 节点ID映射表
 * 
 * 维护节点ID到节点对象的映射，用于快速查找
 */
class NodeIdMap {
    
    private val map = mutableMapOf<String, UiNode>()
    
    /**
     * 添加节点
     */
    fun put(nodeId: String, node: UiNode) {
        map[nodeId] = node
    }
    
    /**
     * 获取节点
     */
    fun get(nodeId: String): UiNode? {
        return map[nodeId]
    }
    
    /**
     * 检查节点是否存在
     */
    fun contains(nodeId: String): Boolean {
        return map.containsKey(nodeId)
    }
    
    /**
     * 移除节点
     */
    fun remove(nodeId: String): UiNode? {
        return map.remove(nodeId)
    }
    
    /**
     * 获取所有节点ID
     */
    fun getAllNodeIds(): Set<String> {
        return map.keys.toSet()
    }
    
    /**
     * 清空映射
     */
    fun clear() {
        map.clear()
    }
    
    /**
     * 获取大小
     */
    fun size(): Int {
        return map.size
    }
    
    /**
     * 构建扁平化映射（递归）
     */
    fun buildFromSnapshot(snapshot: UiSnapshot) {
        clear()
        addNodeRecursive(snapshot.root)
    }
    
    /**
     * 递归添加节点
     */
    private fun addNodeRecursive(node: UiNode) {
        map[node.nodeId] = node
        node.children.forEach { child ->
            addNodeRecursive(child)
        }
    }
}
