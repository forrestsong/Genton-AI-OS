package com.ainative.inference;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * EgressPlan - 出网计划（隐私审计）
 * 
 * preflightEgress 返回的出网字段清单，用于审计与用户透明。
 * 
 * 设计原则：
 * 1. 字段白名单化：明确列出将外发的字段名
 * 2. 脱敏透明：标记哪些字段已脱敏处理
 * 3. 本地优先：如果可本地完成则 localOnly=true
 * 4. 增量最小：仅发送增量字段，不重传完整上下文
 * 
 * 对应任务：A9.1
 * 草案：轨道 A §5 数据离开策略
 */
public final class EgressPlan implements Parcelable {
    /**
     * 实际将外发的字段名清单
     * 例如：["intentId", "contextDelta", "maxTokens", "costCeiling"]
     * 
     * 不包含完整 prompt（使用 contextRef 引用替代）
     */
    public String[] fieldsToSend;
    
    /**
     * 已脱敏的字段名清单
     * 例如：["userName", "phoneNumber"]
     * 
     * 这些字段已经过 PII 防火墙处理（哈希/截断/泛化）
     */
    public String[] fieldsMasked;
    
    /**
     * 是否可本地完成（无需出网）
     * true: 本地推理可满足，不会外发任何数据
     * false: 需要云端推理，将按 fieldsToSend 外发
     */
    public boolean localOnly;
    
    /**
     * 预估出网字节数（用于流量监控）
     */
    public long estimatedBytesSent;
    
    /**
     * 隐私级别（0=公开 1=敏感 2=极敏感）
     * 极敏感数据强制 localOnly
     */
    public int privacyLevel;

    public EgressPlan() {
        this.fieldsToSend = new String[0];
        this.fieldsMasked = new String[0];
        this.localOnly = true;
        this.estimatedBytesSent = 0;
        this.privacyLevel = 0;
    }

    protected EgressPlan(Parcel in) {
        fieldsToSend = in.createStringArray();
        fieldsMasked = in.createStringArray();
        localOnly = in.readBoolean();
        estimatedBytesSent = in.readLong();
        privacyLevel = in.readInt();
    }

    /**
     * 供 AIDL 生成代码以 out / inout 参数形式回填本对象
     * （parcelable 骨架声明未生成 readFromParcel，需手写实现）
     */
    public void readFromParcel(Parcel in) {
        fieldsToSend = in.createStringArray();
        fieldsMasked = in.createStringArray();
        localOnly = in.readBoolean();
        estimatedBytesSent = in.readLong();
        privacyLevel = in.readInt();
    }

    public static final Creator<EgressPlan> CREATOR = new Creator<EgressPlan>() {
        @Override
        public EgressPlan createFromParcel(Parcel in) {
            return new EgressPlan(in);
        }

        @Override
        public EgressPlan[] newArray(int size) {
            return new EgressPlan[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringArray(fieldsToSend);
        dest.writeStringArray(fieldsMasked);
        dest.writeBoolean(localOnly);
        dest.writeLong(estimatedBytesSent);
        dest.writeInt(privacyLevel);
    }

    @Override
    public String toString() {
        return "EgressPlan{" +
                "fieldsToSend=" + Arrays.toString(fieldsToSend) +
                ", fieldsMasked=" + Arrays.toString(fieldsMasked) +
                ", localOnly=" + localOnly +
                ", estimatedBytes=" + estimatedBytesSent +
                ", privacyLevel=" + privacyLevel +
                '}';
    }
}
