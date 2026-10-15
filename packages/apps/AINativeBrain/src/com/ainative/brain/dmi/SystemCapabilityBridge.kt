package com.ainative.brain.dmi

/**
 * 系统能力桥接口
 * 
 * 所有系统能力桥的基础接口
 */
interface SystemCapabilityBridge {
    
    /**
     * 执行系统能力调用
     * 
     * @param parameters 参数映射表
     * @param callback 执行回调
     */
    fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback)
    
    /**
     * 验证参数
     * 
     * @param parameters 参数映射表
     * @return 验证结果（null 表示成功，否则返回错误信息）
     */
    fun validateParameters(parameters: Map<String, Any>): String?
    
    /**
     * 检查权限
     * 
     * @return 权限检查结果（null 表示有权限，否则返回缺失的权限）
     */
    fun checkPermissions(): String?
}

/**
 * DMI 执行回调接口
 */
interface DmiExecutionCallback {
    
    /**
     * 执行成功
     * 
     * @param resultJson 结果 JSON 字符串
     */
    fun onSuccess(resultJson: String)
    
    /**
     * 执行失败
     * 
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     */
    fun onFailure(errorCode: Int, errorMessage: String)
}
