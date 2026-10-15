// Genton (AI Native OS) - 动作回调实现
//
// [P4 补齐] ActionExecutor 引用了此类但仓库中缺失。
// 负责：保存 ACB 返回的 actionRequestId，并按超时等待执行完成并汇总结果。
package com.genton.brain.executor

import android.util.Log
import com.genton.brain.toolloop.ExecutionContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 单次动作执行的回调与等待句柄 */
class ActionCallbackImpl(
    val actionId: String,
    val context: ExecutionContext
) {
    companion object {
        private const val TAG = "ActionCallbackImpl"
    }

    /** 由 ACB 受理后回填 */
    @Volatile
    var actionRequestId: String? = null

    /** 终态状态（由 ACB 状态回调填充） */
    @Volatile
    var lastStatus: ActionStatusSnapshot? = null

    private val done = CountDownLatch(1)

    /** 标记完成（由状态回调调用） */
    fun markDone(status: ActionStatusSnapshot) {
        lastStatus = status
        done.countDown()
    }

    /**
     * 等待执行完成
     *
     * @param timeoutMs 超时毫秒
     * @return 汇总快照；超时返回 null
     */
    fun waitForCompletion(timeoutMs: Long): ActionStatusSnapshot? {
        return try {
            if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) lastStatus else {
                Log.w(TAG, "Action wait timeout: $actionId")
                null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}

/** 动作状态快照（供执行结果汇总） */
data class ActionStatusSnapshot(
    val state: Int = 0,
    val errorMessage: String? = null,
    val tokensConsumed: Int = 0,
    val energyConsumed: Int = 0,
    val wallTimeMs: Long = 0L
)
