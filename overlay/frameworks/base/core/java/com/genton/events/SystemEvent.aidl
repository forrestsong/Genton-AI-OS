package com.genton.events;

import android.os.Bundle;

/**
 * SystemEvent - 系统事件
 * 
 * 表示系统中发生的事件（通知/前台/屏幕/通话/日程/系统/位置/Agent/语义）。
 */
parcelable SystemEvent {
    long eventId;
    long seq;                        // 订阅内单调（保证有序）
    String type;                     // 事件类型（白名单）
    int priority;                    // PRIORITY_LOW/NORMAL/HIGH
    int sensitivity;                 // S0/S1/S2
    long timestampRealtimeNanos;
    String sourcePackage;            // 事件源包名
    Bundle payload;                  // 仅摘要级/匿名化负载
    int trust;                       // TrustLevel（来源 taint）
    boolean fromUserTurn;            // 是否由用户当前操作触发
}
