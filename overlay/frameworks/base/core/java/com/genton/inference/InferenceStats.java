package com.genton.inference;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * InferenceStats - 推理统计
 * 
 * 推理运行时统计信息，用于监控和仪表盘展示
 */
public class InferenceStats implements Parcelable {
    /** 总推理请求数 */
    public long totalRequests;
    
    /** 成功数 */
    public long successfulRequests;
    
    /** 失败数 */
    public long failedRequests;
    
    /** 本地 tiny 推理数 */
    public long localTinyCount;
    
    /** 本地 large 推理数 */
    public long localLargeCount;
    
    /** 云端推理数 */
    public long cloudCount;
    
    /** 总消耗 token 数 */
    public long totalTokens;
    
    /** 总消耗能量（微焦耳） */
    public long totalEnergyMicroJoules;
    
    /** 总消耗成本（微分） */
    public long totalCostMicros;
    
    /** 平均延迟（毫秒） */
    public float avgLatencyMs;
    
    /** P95 延迟（毫秒） */
    public float p95LatencyMs;
    
    /** 当前队列长度 */
    public int queueLength;
    
    /** NPU 利用率（0.0-1.0） */
    public float npuUtilization;
    
    /** 内存使用率（0.0-1.0） */
    public float memoryUtilization;
    
    /** 网络可用性（0=不可用，1=可用） */
    public boolean networkAvailable;
    
    /** 电池电量（0-100） */
    public int batteryLevel;
    
    /** 是否充电中 */
    public boolean isCharging;
    
    public InferenceStats() {
    }
    
    protected InferenceStats(Parcel in) {
        totalRequests = in.readLong();
        successfulRequests = in.readLong();
        failedRequests = in.readLong();
        localTinyCount = in.readLong();
        localLargeCount = in.readLong();
        cloudCount = in.readLong();
        totalTokens = in.readLong();
        totalEnergyMicroJoules = in.readLong();
        totalCostMicros = in.readLong();
        avgLatencyMs = in.readFloat();
        p95LatencyMs = in.readFloat();
        queueLength = in.readInt();
        npuUtilization = in.readFloat();
        memoryUtilization = in.readFloat();
        networkAvailable = in.readByte() != 0;
        batteryLevel = in.readInt();
        isCharging = in.readByte() != 0;
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(totalRequests);
        dest.writeLong(successfulRequests);
        dest.writeLong(failedRequests);
        dest.writeLong(localTinyCount);
        dest.writeLong(localLargeCount);
        dest.writeLong(cloudCount);
        dest.writeLong(totalTokens);
        dest.writeLong(totalEnergyMicroJoules);
        dest.writeLong(totalCostMicros);
        dest.writeFloat(avgLatencyMs);
        dest.writeFloat(p95LatencyMs);
        dest.writeInt(queueLength);
        dest.writeFloat(npuUtilization);
        dest.writeFloat(memoryUtilization);
        dest.writeByte((byte) (networkAvailable ? 1 : 0));
        dest.writeInt(batteryLevel);
        dest.writeByte((byte) (isCharging ? 1 : 0));
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<InferenceStats> CREATOR = new Creator<InferenceStats>() {
        @Override
        public InferenceStats createFromParcel(Parcel in) {
            return new InferenceStats(in);
        }
        
        @Override
        public InferenceStats[] newArray(int size) {
            return new InferenceStats[size];
        }
    };
    
    @Override
    public String toString() {
        return "InferenceStats{" +
                "totalRequests=" + totalRequests +
                ", successfulRequests=" + successfulRequests +
                ", failedRequests=" + failedRequests +
                ", localTinyCount=" + localTinyCount +
                ", localLargeCount=" + localLargeCount +
                ", cloudCount=" + cloudCount +
                ", totalTokens=" + totalTokens +
                ", totalEnergyMicroJoules=" + totalEnergyMicroJoules +
                ", totalCostMicros=" + totalCostMicros +
                ", avgLatencyMs=" + avgLatencyMs +
                ", p95LatencyMs=" + p95LatencyMs +
                ", queueLength=" + queueLength +
                ", npuUtilization=" + npuUtilization +
                ", memoryUtilization=" + memoryUtilization +
                ", networkAvailable=" + networkAvailable +
                ", batteryLevel=" + batteryLevel +
                ", isCharging=" + isCharging +
                '}';
    }
}
