package com.genton.motion;

import com.genton.motion.GestureEvent;

/**
 * IMotionGestureListener - 手势事件监听回调
 */
interface IMotionGestureListener {
    /**
     * 手势事件触发
     * @param event 手势事件
     */
    void onGestureDetected(in GestureEvent event);
}
