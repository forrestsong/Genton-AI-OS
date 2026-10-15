/**
 * A3.4 Taint规则初始化
 * 
 * 在系统启动时初始化所有默认的taint规则
 */
package com.android.server.genton.action

import com.genton.action.TaintModels.TaintRuleSet

/**
 * Taint规则初始化器
 * 
 * 在AgentActionService初始化时调用，加载所有默认taint规则
 */
object TaintRuleInitializer {
    
    private var initialized = false
    
    /**
     * 初始化所有taint规则
     * 
     * 必须在AgentActionService创建时调用
     */
    fun initialize() {
        if (initialized) {
            return
        }
        
        // 加载默认规则
        TaintRuleSet.initializeDefaultRules()
        
        initialized = true
    }
    
    /**
     * 重置（仅测试用）
     */
    fun resetForTesting() {
        TaintRuleSet.clearForTesting()
        initialized = false
    }
}
