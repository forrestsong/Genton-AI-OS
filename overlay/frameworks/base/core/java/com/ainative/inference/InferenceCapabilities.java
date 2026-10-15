package com.ainative.inference;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * InferenceCapabilities - 推理能力
 * 
 * 描述设备推理能力，包括档位支持、模型列表、NPU 状态等
 */
public class InferenceCapabilities implements Parcelable {
    /** 支持的档位掩码 */
    public int supportedTiers;
    
    /** NPU 是否可用 */
    public boolean npuAvailable;
    
    /** NPU 算力（TOPS） */
    public float npuTops;
    
    /** 内存总量（MB） */
    public int totalMemoryMb;
    
    /** 可用内存（MB） */
    public int availableMemoryMb;
    
    /** 已加载模型列表 */
    public List<String> loadedModels = new ArrayList<>();
    
    /** 支持的模型列表 */
    public List<String> supportedModels = new ArrayList<>();
    
    /** 是否支持流式输出 */
    public boolean supportsStreaming = true;
    
    /** 是否支持 KV 缓存 */
    public boolean supportsKVCache = true;
    
    /** 是否支持 Prefix 缓存 */
    public boolean supportsPrefixCache = true;
    
    public InferenceCapabilities() {
    }
    
    protected InferenceCapabilities(Parcel in) {
        supportedTiers = in.readInt();
        npuAvailable = in.readByte() != 0;
        npuTops = in.readFloat();
        totalMemoryMb = in.readInt();
        availableMemoryMb = in.readInt();
        loadedModels = in.createStringArrayList();
        supportedModels = in.createStringArrayList();
        supportsStreaming = in.readByte() != 0;
        supportsKVCache = in.readByte() != 0;
        supportsPrefixCache = in.readByte() != 0;
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(supportedTiers);
        dest.writeByte((byte) (npuAvailable ? 1 : 0));
        dest.writeFloat(npuTops);
        dest.writeInt(totalMemoryMb);
        dest.writeInt(availableMemoryMb);
        dest.writeStringList(loadedModels);
        dest.writeStringList(supportedModels);
        dest.writeByte((byte) (supportsStreaming ? 1 : 0));
        dest.writeByte((byte) (supportsKVCache ? 1 : 0));
        dest.writeByte((byte) (supportsPrefixCache ? 1 : 0));
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<InferenceCapabilities> CREATOR = new Creator<InferenceCapabilities>() {
        @Override
        public InferenceCapabilities createFromParcel(Parcel in) {
            return new InferenceCapabilities(in);
        }
        
        @Override
        public InferenceCapabilities[] newArray(int size) {
            return new InferenceCapabilities[size];
        }
    };
    
    @Override
    public String toString() {
        return "InferenceCapabilities{" +
                "supportedTiers=0x" + Integer.toHexString(supportedTiers) +
                ", npuAvailable=" + npuAvailable +
                ", npuTops=" + npuTops +
                ", totalMemoryMb=" + totalMemoryMb +
                ", availableMemoryMb=" + availableMemoryMb +
                ", loadedModels=" + loadedModels.size() +
                ", supportedModels=" + supportedModels.size() +
                ", supportsStreaming=" + supportsStreaming +
                ", supportsKVCache=" + supportsKVCache +
                ", supportsPrefixCache=" + supportsPrefixCache +
                '}';
    }
}
