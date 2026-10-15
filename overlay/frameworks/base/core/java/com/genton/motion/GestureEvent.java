package com.genton.motion;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * GestureEvent - 手势事件
 * 
 * 封装手势检测结果，包含手势类型、时间戳、置信度等信息
 */
public class GestureEvent implements Parcelable {
    // 手势类型（与 MotionHubService 常量对齐）
    public static final int GESTURE_SHAKE = 1 << 0;  // 摇一摇就绪
    public static final int GESTURE_FLIP = 1 << 1;   // 翻面备忘
    public static final int GESTURE_SWIPE = 1 << 2;  // 划动手势
    
    /** 手势类型 */
    public int gestureType;
    
    /** 事件时间戳（System.currentTimeMillis()） */
    public long timestamp;
    
    /** 置信度（0.0-1.0） */
    public float confidence;
    
    /** 元数据（JSON 格式，包含额外信息如 gForce/angle/direction） */
    public String metadata;
    
    public GestureEvent() {
    }
    
    protected GestureEvent(Parcel in) {
        gestureType = in.readInt();
        timestamp = in.readLong();
        confidence = in.readFloat();
        metadata = in.readString();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(gestureType);
        dest.writeLong(timestamp);
        dest.writeFloat(confidence);
        dest.writeString(metadata);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<GestureEvent> CREATOR = new Creator<GestureEvent>() {
        @Override
        public GestureEvent createFromParcel(Parcel in) {
            return new GestureEvent(in);
        }
        
        @Override
        public GestureEvent[] newArray(int size) {
            return new GestureEvent[size];
        }
    };
    
    @Override
    public String toString() {
        return "GestureEvent{" +
                "gestureType=" + getGestureTypeName(gestureType) +
                ", timestamp=" + timestamp +
                ", confidence=" + confidence +
                ", metadata='" + metadata + '\'' +
                '}';
    }
    
    private static String getGestureTypeName(int type) {
        switch (type) {
            case GESTURE_SHAKE:
                return "SHAKE";
            case GESTURE_FLIP:
                return "FLIP";
            case GESTURE_SWIPE:
                return "SWIPE";
            default:
                return "UNKNOWN(0x" + Integer.toHexString(type) + ")";
        }
    }
}
