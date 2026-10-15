package com.genton.inference;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * AttestationReport - 设备远程证明报告
 * 
 * 设备远程证明（Remote Attestation）quote，用于云端验证设备完整性。
 * 
 * 设计原则：
 * 1. 零知识证明：不泄露设备 ID，仅证明"是合法设备"
 * 2. 时效性：带时间戳，防重放攻击
 * 3. TEE 支持：优先使用硬件 TEE（TrustZone/SGX），回退到软件证明
 * 4. 双向认证：设备证明自己，云端也需证明自己（TLS 证书固定）
 * 
 * 对应任务：A9.1
 * 草案：轨道 A §4 CloudBroker 云侧契约（mTLS + 设备远程证明）
 */
public final class AttestationReport implements Parcelable {
    /**
     * 证明类型
     * 0: 软件证明（模拟，用于测试）
     * 1: TEE 证明（TrustZone）
     * 2: 硬件证明（TPM/Titan）
     */
    public int attestationType;
    
    /**
     * 证明 quote（签名的设备完整性报告）
     * TEE 生成的签名数据，包含：
     * - 设备公钥哈希（不含完整 ID）
     * - 系统完整性度量值（boot chain hash）
     * - 时间戳
     * - nonce（防重放）
     */
    public byte[] quote;
    
    /**
     * 签名（TEE 私钥签名）
     * 用于验证 quote 的真实性
     */
    public byte[] signature;
    
    /**
     * 时间戳（Unix 毫秒）
     * 用于检测证明时效性
     */
    public long timestampMs;
    
    /**
     * nonce（随机数，防重放）
     * CloudGateway 生成，设备签入 quote
     */
    public byte[] nonce;
    
    /**
     * 设备公钥哈希（SHA-256）
     * 用于云端识别设备会话，但不泄露完整设备 ID
     */
    public byte[] deviceKeyHash;
    
    /**
     * 证明状态
     * 0: 成功
     * 1: TEE 不可用（回退到软件证明）
     * 2: 完整性校验失败
     * -1: 内部错误
     */
    public int status;

    public AttestationReport() {
        this.attestationType = 0;
        this.quote = new byte[0];
        this.signature = new byte[0];
        this.timestampMs = System.currentTimeMillis();
        this.nonce = new byte[0];
        this.deviceKeyHash = new byte[0];
        this.status = 0;
    }

    protected AttestationReport(Parcel in) {
        attestationType = in.readInt();
        quote = in.createByteArray();
        signature = in.createByteArray();
        timestampMs = in.readLong();
        nonce = in.createByteArray();
        deviceKeyHash = in.createByteArray();
        status = in.readInt();
    }

    public static final Creator<AttestationReport> CREATOR = new Creator<AttestationReport>() {
        @Override
        public AttestationReport createFromParcel(Parcel in) {
            return new AttestationReport(in);
        }

        @Override
        public AttestationReport[] newArray(int size) {
            return new AttestationReport[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(attestationType);
        dest.writeByteArray(quote);
        dest.writeByteArray(signature);
        dest.writeLong(timestampMs);
        dest.writeByteArray(nonce);
        dest.writeByteArray(deviceKeyHash);
        dest.writeInt(status);
    }

    @Override
    public String toString() {
        return "AttestationReport{" +
                "type=" + attestationType +
                ", quoteLen=" + quote.length +
                ", sigLen=" + signature.length +
                ", timestamp=" + timestampMs +
                ", nonceLen=" + nonce.length +
                ", keyHashLen=" + deviceKeyHash.length +
                ", status=" + status +
                '}';
    }
}
