package com.ainative.inference;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * InferenceChunk - 推理输出块
 * 
 * 流式推理输出块，支持文本/工具调用/用量估计三种类型
 */
public class InferenceChunk implements Parcelable {
    // 输出块类型
    public static final int TYPE_TEXT = 0;           // 文本输出
    public static final int TYPE_TOOL_CALL = 1;      // 工具调用
    public static final int TYPE_USAGE_ESTIMATE = 2; // 用量估计
    
    /** 块类型 */
    public int type;
    
    /** 文本内容（TYPE_TEXT） */
    public String text;
    
    /** 工具调用 JSON（TYPE_TOOL_CALL） */
    public String toolJson;
    
    /** 已消耗 token 数（累计） */
    public long tokensSoFar;
    
    /** 已消耗能量（微焦耳，累计） */
    public long energyMicroJoules;
    
    /** 已消耗成本（微分，累计） */
    public long costMicros;
    
    public InferenceChunk() {
    }
    
    protected InferenceChunk(Parcel in) {
        type = in.readInt();
        text = in.readString();
        toolJson = in.readString();
        tokensSoFar = in.readLong();
        energyMicroJoules = in.readLong();
        costMicros = in.readLong();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeString(text);
        dest.writeString(toolJson);
        dest.writeLong(tokensSoFar);
        dest.writeLong(energyMicroJoules);
        dest.writeLong(costMicros);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<InferenceChunk> CREATOR = new Creator<InferenceChunk>() {
        @Override
        public InferenceChunk createFromParcel(Parcel in) {
            return new InferenceChunk(in);
        }
        
        @Override
        public InferenceChunk[] newArray(int size) {
            return new InferenceChunk[size];
        }
    };
    
    @Override
    public String toString() {
        return "InferenceChunk{" +
                "type=" + getTypeName(type) +
                ", text='" + (text != null ? text.substring(0, Math.min(text.length(), 50)) : null) + "'" +
                ", tokensSoFar=" + tokensSoFar +
                ", energyMicroJoules=" + energyMicroJoules +
                ", costMicros=" + costMicros +
                '}';
    }
    
    private static String getTypeName(int type) {
        switch (type) {
            case TYPE_TEXT:
                return "TEXT";
            case TYPE_TOOL_CALL:
                return "TOOL_CALL";
            case TYPE_USAGE_ESTIMATE:
                return "USAGE_ESTIMATE";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }
}
