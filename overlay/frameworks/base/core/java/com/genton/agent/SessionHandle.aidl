// SessionHandle.aidl
// 会话句柄
package com.genton.agent;

/**
 * 会话句柄
 * 
 * openSession() 返回，后续 submitUserTurn/cancelTurn/endSession 使用
 */
parcelable SessionHandle {
    /**
     * 会话标识（与 SessionOpenRequest.sessionId 一致）
     */
    String sessionId;
    
    /**
     * 拥有者 Agent ID
     */
    String ownerAgentId;
}
