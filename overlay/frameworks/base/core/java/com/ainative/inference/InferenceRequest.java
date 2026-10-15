package com.ainative.inference;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * InferenceRequest - 推理请求
 * 
 * 封装推理请求参数，支持约束位控制、上下文引用、预算上限等
 */
public class InferenceRequest implements Parcelable {
    // 约束位掩码
    public static final int CONSTRAINT_LOCAL_ONLY = 1 << 0;     // 仅本地（不可出端）
    public static final int CONSTRAINT_CLOUD_DENIED = 1 << 1;   // 禁止云端
    public static final int CONSTRAINT_OFFLINE_OK = 1 << 2;     // 可离线
    public static final int CONSTRAINT_LOW_ENERGY = 1 << 3;     // 低功耗模式
    
    /** 请求 ID（由调用方生成） */
    public String requestId;
    
    /** Agent ID */
    public String agentId;
    
    /** Intent ID（scope/审计锚定） */
    public String intentId;
    
    /** 建议档位（最终以约束裁决） */
    public int suggestedTier;
    
    /** 约束掩码 */
    public int constraintsMask;
    
    /** 提示文本 */
    public String prompt;
    
    /** 系统提示 */
    public String systemPrompt;
    
    /** 最大 token 数 */
    public int maxTokens;
    
    /** 温度参数（0.0-2.0） */
    public float temperature = 0.7f;
    
    /** 成本上限（微分，不得超过 ACB budget） */
    public long costCeilingMicros;
    
    /** 是否流式输出 */
    public boolean allowStream = true;
    
    /** 模型名称（可选，空则自动选择） */
    public String modelName;
    
    /** 上下文引用（可选，用于 KV 复用） */
    public String contextRef;
    
    public InferenceRequest() {
    }
    
    protected InferenceRequest(Parcel in) {
        requestId = in.readString();
        agentId = in.readString();
        intentId = in.readString();
        suggestedTier = in.readInt();
        constraintsMask = in.readInt();
        prompt = in.readString();
        systemPrompt = in.readString();
        maxTokens = in.readInt();
        temperature = in.readFloat();
        costCeilingMicros = in.readLong();
        allowStream = in.readByte() != 0;
        modelName = in.readString();
        contextRef = in.readString();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(agentId);
        dest.writeString(intentId);
        dest.writeInt(suggestedTier);
        dest.writeInt(constraintsMask);
        dest.writeString(prompt);
        dest.writeString(systemPrompt);
        dest.writeInt(maxTokens);
        dest.writeFloat(temperature);
        dest.writeLong(costCeilingMicros);
        dest.writeByte((byte) (allowStream ? 1 : 0));
        dest.writeString(modelName);
        dest.writeString(contextRef);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<InferenceRequest> CREATOR = new Creator<InferenceRequest>() {
        @Override
        public InferenceRequest createFromParcel(Parcel in) {
            return new InferenceRequest(in);
        }
        
        @Override
        public InferenceRequest[] newArray(int size) {
            return new InferenceRequest[size];
        }
    };
    
    @Override
    public String toString() {
        return "InferenceRequest{" +
                "requestId='" + requestId + '\'' +
                ", agentId='" + agentId + '\'' +
                ", intentId='" + intentId + '\'' +
                ", suggestedTier=" + suggestedTier +
                ", constraintsMask=0x" + Integer.toHexString(constraintsMask) +
                ", maxTokens=" + maxTokens +
                ", temperature=" + temperature +
                ", costCeilingMicros=" + costCeilingMicros +
                ", allowStream=" + allowStream +
                ", modelName='" + modelName + '\'' +
                '}';
    }
}
