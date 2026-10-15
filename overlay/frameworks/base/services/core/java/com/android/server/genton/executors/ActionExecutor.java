package com.android.server.genton.executors;

import com.genton.action.ActionRequest;

/**
 * 动作执行器接口
 * 
 * 所有具体执行器（ActivityExecutor/InputExecutor/DeviceControlExecutor 等）
 * 必须实现此接口，由 ActionExecutorDispatcher 统一调度。
 * 
 * 设计原则：
 * 1. 执行器无状态（每次调用独立）
 * 2. 同步执行（阻塞调用，由调度器管理线程池）
 * 3. 异常安全（不抛出异常，通过 ExecutionResult 返回错误）
 * 4. 幂等性（相同参数重复调用结果一致）
 * 
 * @see ActionExecutorDispatcher
 */
public interface ActionExecutor {
    
    /**
     * 获取执行器类型
     * 
     * @return 执行器类型标识（如 "activity" / "input" / "device_control"）
     */
    String getExecutorType();
    
    /**
     * 执行动作
     * 
     * @param request 动作请求（包含 actionId/arguments/token）
     * @param context 执行上下文（包含超时/取消标志）
     * @return 执行结果（成功/失败 + 结果 JSON）
     */
    ExecutionResult execute(ActionRequest request, ExecutionContext context);
    
    /**
     * 检查是否支持指定动作
     * 
     * @param actionId 动作 ID
     * @return 是否支持
     */
    boolean supportsAction(String actionId);
    
    /**
     * 执行结果
     */
    class ExecutionResult {
        public boolean success;
        public String resultJson;  // 成功时的结果数据
        public String errorMessage;  // 失败时的错误信息
        
        public static ExecutionResult success(String resultJson) {
            ExecutionResult result = new ExecutionResult();
            result.success = true;
            result.resultJson = resultJson;
            return result;
        }
        
        public static ExecutionResult failure(String errorMessage) {
            ExecutionResult result = new ExecutionResult();
            result.success = false;
            result.errorMessage = errorMessage;
            return result;
        }
    }
    
    /**
     * 执行上下文
     */
    class ExecutionContext {
        public long timeoutMs;  // 超时时间
        public volatile boolean cancelled;  // 取消标志
        
        public ExecutionContext(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            this.cancelled = false;
        }
        
        public boolean isCancelled() {
            return cancelled;
        }
        
        public void cancel() {
            this.cancelled = true;
        }
    }
}
