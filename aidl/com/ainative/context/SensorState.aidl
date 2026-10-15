// SensorState.aidl
package com.ainative.context;

/**
 * 传感器状态
 */
parcelable SensorState {
    /**
     * 屏幕状态（0=关闭，1=打开，2=锁屏）
     */
    int screenState;

    /**
     * 电量百分比（0-100）
     */
    int batteryPercent;

    /**
     * 是否正在充电
     */
    boolean isCharging;

    /**
     * 网络类型（wifi/mobile/none）
     */
    String networkType;

    /**
     * 铃声模式（normal/vibrate/silent）
     */
    String ringerMode;

    /**
     * 是否连接耳机
     */
    boolean hasHeadset;

    /**
     * 是否处于免打扰模式
     */
    boolean isDoNotDisturb;
}
