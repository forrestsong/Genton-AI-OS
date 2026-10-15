package com.genton.motion;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * MotionTierState - 档位状态
 * 
 * 封装 MotionHub 当前档位状态、设备能力位、手势开关等信息
 */
public class MotionTierState implements Parcelable {
    // 档位常量
    public static final int TIER_FOREGROUND = 1;        // T1: 亮屏前台
    public static final int TIER_DEEP_SLEEP = 2;        // T2: 深睡事件
    public static final int TIER_SENSITIVE_STOP = 3;    // T3: 敏感全停
    
    /** 当前档位 */
    public int currentTier;
    
    /** 设备能力位（32 位掩码） */
    public int capabilities;
    
    /** 摇一摇手势开关 */
    public boolean shakeEnabled;
    
    /** 翻面手势开关 */
    public boolean flipEnabled;
    
    /** 划动手势开关 */
    public boolean swipeEnabled;
    
    /** 当前功耗（mW） */
    public float currentPowerMw;
    
    /** 敏感场景原因（T3 档位时非空） */
    public String sensitiveReason;
    
    public MotionTierState() {
    }
    
    protected MotionTierState(Parcel in) {
        currentTier = in.readInt();
        capabilities = in.readInt();
        shakeEnabled = in.readByte() != 0;
        flipEnabled = in.readByte() != 0;
        swipeEnabled = in.readByte() != 0;
        currentPowerMw = in.readFloat();
        sensitiveReason = in.readString();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(currentTier);
        dest.writeInt(capabilities);
        dest.writeByte((byte) (shakeEnabled ? 1 : 0));
        dest.writeByte((byte) (flipEnabled ? 1 : 0));
        dest.writeByte((byte) (swipeEnabled ? 1 : 0));
        dest.writeFloat(currentPowerMw);
        dest.writeString(sensitiveReason);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<MotionTierState> CREATOR = new Creator<MotionTierState>() {
        @Override
        public MotionTierState createFromParcel(Parcel in) {
            return new MotionTierState(in);
        }
        
        @Override
        public MotionTierState[] newArray(int size) {
            return new MotionTierState[size];
        }
    };
    
    @Override
    public String toString() {
        return "MotionTierState{" +
                "currentTier=" + getTierName(currentTier) +
                ", capabilities=0x" + Integer.toHexString(capabilities) +
                ", shakeEnabled=" + shakeEnabled +
                ", flipEnabled=" + flipEnabled +
                ", swipeEnabled=" + swipeEnabled +
                ", currentPowerMw=" + currentPowerMw +
                ", sensitiveReason='" + sensitiveReason + '\'' +
                '}';
    }
    
    private static String getTierName(int tier) {
        switch (tier) {
            case TIER_FOREGROUND:
                return "T1_FOREGROUND";
            case TIER_DEEP_SLEEP:
                return "T2_DEEP_SLEEP";
            case TIER_SENSITIVE_STOP:
                return "T3_SENSITIVE_STOP";
            default:
                return "UNKNOWN(" + tier + ")";
        }
    }
}
