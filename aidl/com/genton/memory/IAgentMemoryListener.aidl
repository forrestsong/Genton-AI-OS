package com.genton.memory;

import com.genton.memory.MemoryEntry;
import com.genton.memory.ForgetJob;

/**
 * 记忆变更监听器
 * 
 * 大脑/SystemUI 按需订阅记忆变更
 */
interface IAgentMemoryListener {
    /**
     * 记忆写入通知（脱敏摘要）
     */
    oneway void onMemoryWritten(in MemoryEntry entry);
    
    /**
     * 记忆删除通知
     */
    oneway void onMemoryDeleted(String memoryId, String agentId);
    
    /**
     * 遗忘任务完成通知（job 含删除统计与云端同步状态）
     */
    oneway void onForgetCompleted(in ForgetJob job);
}
