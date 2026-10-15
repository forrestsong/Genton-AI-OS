// CallSessionHandle.aidl
package com.genton.call;

/**
 * 通话会话句柄
 */
parcelable CallSessionHandle {
    /**
     * 会话 ID（唯一标识）
     */
    String sessionId;

    /**
     * 号码哈希（关联 CallDescriptor，隐私保护）
     */
    String numberHash;

    /**
     * 会话开始时间（System.nanoTime()，用于计算耗时）
     */
    long startedAtRealtimeNanos;

    /**
     * 接管模式（1=L1, 2=L2, 3=L3）
     */
    int handleMode;
}
