// AI Native OS - Form State Machine (D5.2)
package com.android.systemui.ainative.form;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ainative.agent.FormPayload;
import com.ainative.agent.FormValueEntry;

/**
 * 表单状态机：管理表单生命周期状态（D5.2）
 * 
 * 状态转换：
 * awaiting_input → validating → submitting → done
 *                     ↓            ↓
 *                  awaiting_input  canceled
 * 
 * 状态说明：
 * 0 = awaiting_input（等待用户输入）
 * 1 = validating（校验中）
 * 2 = submitting（提交中）
 * 3 = done（已完成）
 * 4 = canceled（已取消）
 */
public class FormStateMachine {
    private static final String TAG = "FormStateMachine";
    
    // 状态常量
    public static final int STATE_AWAITING_INPUT = 0;
    public static final int STATE_VALIDATING = 1;
    public static final int STATE_SUBMITTING = 2;
    public static final int STATE_DONE = 3;
    public static final int STATE_CANCELED = 4;
    
    private static final String[] STATE_NAMES = {
        "awaiting_input", "validating", "submitting", "done", "canceled"
    };
    
    private final Handler mHandler;
    
    private FormPayload mFormPayload;
    private int mCurrentState;
    private FormStateListener mStateListener;
    
    public interface FormStateListener {
        void onStateChanged(int oldState, int newState, FormPayload formPayload);
        void onValidationResult(boolean success, String error);
        void onSubmitComplete(boolean success, String message);
    }
    
    public FormStateMachine() {
        mHandler = new Handler(Looper.getMainLooper());
        mCurrentState = STATE_AWAITING_INPUT;
    }
    
    /**
     * 初始化表单
     */
    public void initialize(FormPayload formPayload) {
        mFormPayload = formPayload;
        mCurrentState = formPayload.status;
        Log.i(TAG, "Form state machine initialized: " + formPayload.formId + 
            ", state=" + STATE_NAMES[mCurrentState]);
    }
    
    /**
     * 请求校验
     */
    public void validate(FormValueEntry[] values) {
        if (mCurrentState != STATE_AWAITING_INPUT) {
            Log.w(TAG, "Cannot validate from state: " + STATE_NAMES[mCurrentState]);
            return;
        }
        
        transitionTo(STATE_VALIDATING);
        
        // 模拟异步校验（500ms）
        mHandler.postDelayed(() -> {
            performValidation(values);
        }, 500);
    }
    
    /**
     * 执行校验
     */
    private void performValidation(FormValueEntry[] values) {
        // TODO: 实际校验逻辑（调用schema校验器）
        // 当前简单检查：所有必填项都有值
        
        boolean success = true;
        String error = null;
        
        if (mFormPayload != null && mFormPayload.fields != null) {
            for (com.ainative.agent.FormField field : mFormPayload.fields) {
                if (field.required) {
                    boolean hasValue = false;
                    for (FormValueEntry value : values) {
                        if (value.key.equals(field.key) && value.hasValue) {
                            hasValue = true;
                            break;
                        }
                    }
                    
                    if (!hasValue) {
                        success = false;
                        error = "字段 \"" + field.label + "\" 为必填项";
                        break;
                    }
                }
            }
        }
        
        if (success) {
            // 校验通过，回到awaiting_input等待提交
            transitionTo(STATE_AWAITING_INPUT);
            if (mFormPayload != null) {
                mFormPayload.canSubmit = true;
                mFormPayload.error = null;
            }
        } else {
            // 校验失败，回到awaiting_input要求重新输入
            transitionTo(STATE_AWAITING_INPUT);
            if (mFormPayload != null) {
                mFormPayload.canSubmit = false;
                mFormPayload.error = error;
            }
        }
        
        if (mStateListener != null) {
            mStateListener.onValidationResult(success, error);
        }
        
        Log.d(TAG, "Validation result: " + (success ? "success" : "failed - " + error));
    }
    
    /**
     * 提交表单
     */
    public void submit(FormValueEntry[] values) {
        if (mCurrentState != STATE_AWAITING_INPUT) {
            Log.w(TAG, "Cannot submit from state: " + STATE_NAMES[mCurrentState]);
            return;
        }
        
        if (mFormPayload != null && !mFormPayload.canSubmit) {
            Log.w(TAG, "Form cannot be submitted (canSubmit=false)");
            return;
        }
        
        transitionTo(STATE_SUBMITTING);
        
        // 模拟异步提交（1000ms）
        mHandler.postDelayed(() -> {
            performSubmit(values);
        }, 1000);
    }
    
    /**
     * 执行提交
     */
    private void performSubmit(FormValueEntry[] values) {
        // TODO: 实际提交逻辑（调用AgentSessionService提交表单）
        
        // 模拟提交结果
        boolean success = true;
        String message = "表单提交成功";
        
        if (success) {
            transitionTo(STATE_DONE);
        } else {
            // 提交失败，回到awaiting_input
            transitionTo(STATE_AWAITING_INPUT);
            if (mFormPayload != null) {
                mFormPayload.error = message;
            }
        }
        
        if (mStateListener != null) {
            mStateListener.onSubmitComplete(success, message);
        }
        
        Log.i(TAG, "Submit result: " + (success ? "success" : "failed - " + message));
    }
    
    /**
     * 取消表单
     */
    public void cancel() {
        if (mCurrentState == STATE_DONE || mCurrentState == STATE_CANCELED) {
            Log.w(TAG, "Cannot cancel from state: " + STATE_NAMES[mCurrentState]);
            return;
        }
        
        transitionTo(STATE_CANCELED);
        Log.i(TAG, "Form canceled");
    }
    
    /**
     * 状态转换
     */
    private void transitionTo(int newState) {
        if (mCurrentState == newState) {
            return;
        }
        
        int oldState = mCurrentState;
        mCurrentState = newState;
        
        // 更新FormPayload状态
        if (mFormPayload != null) {
            mFormPayload.status = newState;
        }
        
        Log.d(TAG, "State transition: " + STATE_NAMES[oldState] + " -> " + STATE_NAMES[newState]);
        
        if (mStateListener != null) {
            mStateListener.onStateChanged(oldState, newState, mFormPayload);
        }
    }
    
    /**
     * 获取当前状态
     */
    public int getCurrentState() {
        return mCurrentState;
    }
    
    /**
     * 获取状态名称
     */
    public String getStateName() {
        return STATE_NAMES[mCurrentState];
    }
    
    /**
     * 是否可以提交
     */
    public boolean canSubmit() {
        return mCurrentState == STATE_AWAITING_INPUT && 
               mFormPayload != null && 
               mFormPayload.canSubmit;
    }
    
    /**
     * 设置状态监听器
     */
    public void setStateListener(FormStateListener listener) {
        mStateListener = listener;
    }
    
    /**
     * 重置状态机
     */
    public void reset() {
        mCurrentState = STATE_AWAITING_INPUT;
        if (mFormPayload != null) {
            mFormPayload.status = STATE_AWAITING_INPUT;
            mFormPayload.error = null;
        }
        Log.d(TAG, "State machine reset");
    }
}
