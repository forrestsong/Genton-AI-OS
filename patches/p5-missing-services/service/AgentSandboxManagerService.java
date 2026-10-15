// Genton (AI Native OS) - 沙箱生命周期管理（P5 补齐）
//
// 依据 轨道A-第三方Agent接入面接口草案.md（D14）：
//   第三方 Agent 以沙箱（isolatedProcess + 独立 UID）方式运行，
//   IAgentSandboxManager 负责其生命周期（启动/暂停/撤销/清单视图）。
//
// 说明：本实现负责**清单校验与句柄生命周期管理**（可独立验证的部分）；
//       真实进程拉起依赖 isolatedProcess 与 netd 白名单，属后续接入项。
package com.android.server.ainative;

import android.content.Context;
import android.util.Slog;

import com.ainative.sandbox.IAgentSandboxManager;
import com.ainative.sandbox.SandboxHandle;
import com.ainative.sandbox.SandboxManifest;
import com.ainative.sandbox.SandboxStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 沙箱生命周期管理服务 */
public class AgentSandboxManagerService extends IAgentSandboxManager.Stub {

    private static final String TAG = "AgentSandboxManager";

    /**
     * 沙箱隔离是否已真实实现（WBS 1.8）。
     *
     * 诚实声明：**当前为 false**。真实隔离需要 isolatedProcess 拉起、独立 UID、
     * netd 出网白名单与配套 sepolicy，尚未实现，也无法在无 AOSP 树的环境下验证。
     * 因此本服务对"启动沙箱"一律 fail-closed 拒绝，绝不发放 pid/uid = -1 的
     * "假沙箱句柄"去让调用方以为隔离已经生效。
     */
    private static final boolean ISOLATION_IMPLEMENTED = false;

    /**
     * 单点鉴权（WBS 1.3/1.8）：沙箱管理属高危面（可拉起三方 Agent），
     * 要求调用方持有 com.ainative.permission.MANAGE_SANDBOX（signature|privileged）。
     */
    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws android.os.RemoteException {
        if (code != android.os.IBinder.INTERFACE_TRANSACTION) {
            mContext.enforceCallingPermission("com.ainative.permission.MANAGE_SANDBOX",
                    "AgentSandboxManagerService requires MANAGE_SANDBOX");
        }
        return super.onTransact(code, data, reply, flags);
    }

    private static final int STATE_ACTIVE = 1;
    private static final int STATE_PAUSED = 2;
    private static final int STATE_STOPPED = 3;
    private static final int STATE_IDLE = 0;

    private static final int DEFAULT_MAX_CALLS = 1000;
    private static final long DEFAULT_WALLTIME_MS = 60_000L;

    private final Context mContext;

    /** handleId -> 沙箱记录 */
    private final Map<String, SandboxRecord> mSandboxes = new ConcurrentHashMap<>();

    /** 已注册（已授权）的 Agent 清单：agentId -> manifest */
    private final Map<String, SandboxManifest> mConsented = new ConcurrentHashMap<>();

    /** 沙箱内部记录 */
    private static final class SandboxRecord {
        SandboxHandle handle;
        SandboxManifest manifest;
        int state = STATE_IDLE;
        boolean paused;
        int totalCalls;
        long startedAtMs;

        SandboxStatus toStatus() {
            SandboxStatus s = new SandboxStatus();
            s.handleId = handle.handleId;
            s.agentId = handle.agentId;
            s.agentName = manifest != null ? manifest.agentName : "";
            s.pid = handle.sandboxPid;
            s.uid = handle.sandboxUid;
            s.state = state;
            s.isPaused = paused;
            s.totalCalls = totalCalls;
            s.remainingCalls = handle.remainingCalls;
            s.totalWalltimeMs = System.currentTimeMillis() - startedAtMs;
            return s;
        }
    }

    public AgentSandboxManagerService(Context context) {
        mContext = context;
    }

    /**
     * 记录用户已同意某 Agent 的清单（由确认面板回灌）。
     *
     * WBS 1.8 诚实声明：
     * - 本方法**不做清单签名校验**，只记录"用户已同意"这一事实。
     *   此前 startSandbox 把"已登记 = 已验签"当作签名校验（自证），已在注释中纠正；
     * - 本方法**不在 AIDL 接口内**，仅 system_server 进程内可用。
     *   绝不可把它加入 IAgentSandboxManager —— 那等于开出一个无鉴权的"同意注入"接口。
     */
    public void registerConsent(SandboxManifest manifest) {
        if (manifest != null && manifest.agentId != null) {
            mConsented.put(manifest.agentId, manifest);
            Slog.i(TAG, "consent registered for " + manifest.agentId
                    + " (manifest signature NOT verified)");
        }
    }

    @Override
    public int startSandbox(SandboxManifest manifest, SandboxHandle handleOut) {
        if (manifest == null || manifest.agentId == null) {
            return STATUS_DENIED;
        }
        // 清单签名校验：当前要求调用方已通过确认流程注册（真实签名校验待接入）
        if (!mConsented.containsKey(manifest.agentId)) {
            Slog.w(TAG, "sandbox start denied (no consent): " + manifest.agentId);
            return STATUS_UNSIGNED;
        }
        
        // WBS 1.8：隔离未实现 → fail-closed。
        // 这里**必须**早于任何句柄构造：否则就会发放 pid/uid = -1 的句柄，
        // 让调用方（以及日志、状态面板）以为沙箱已经跑起来了。
        if (!ISOLATION_IMPLEMENTED) {
            Slog.w(TAG, "Sandbox isolation not implemented; refusing to start (fail-closed): "
                    + manifest.agentId);
            return STATUS_DENIED;
        }
        for (SandboxRecord r : mSandboxes.values()) {
            if (r.handle != null && manifest.agentId.equals(r.handle.agentId)
                    && r.state != STATE_STOPPED) {
                Slog.w(TAG, "sandbox already active for " + manifest.agentId);
                if (handleOut != null) {
                    copyHandle(r.handle, handleOut);
                }
                return STATUS_OK;
            }
        }

        SandboxRecord rec = new SandboxRecord();
        rec.manifest = manifest;
        rec.state = STATE_ACTIVE;
        rec.startedAtMs = System.currentTimeMillis();

        SandboxHandle h = new SandboxHandle();
        h.handleId = UUID.randomUUID().toString();
        h.agentId = manifest.agentId;
        h.sandboxPid = -1;   // 仅在 ISOLATION_IMPLEMENTED=true 后才会走到这里；届时必须填真实 pid
        h.sandboxUid = -1;   // 同上：必须填真实隔离 UID
        h.createdAtMs = System.currentTimeMillis();
        h.grantedCapabilities = manifest.requestedCapabilities != null
                ? manifest.requestedCapabilities : new String[0];
        h.remainingCalls = manifest.maxCallsPerHour > 0
                ? manifest.maxCallsPerHour : DEFAULT_MAX_CALLS;
        h.remainingWalltimeMs = manifest.maxWalltimeMs > 0
                ? manifest.maxWalltimeMs : DEFAULT_WALLTIME_MS;
        rec.handle = h;
        mSandboxes.put(h.handleId, rec);

        if (handleOut != null) {
            copyHandle(h, handleOut);
        }
        Slog.i(TAG, "sandbox started: " + h.handleId + " agent=" + manifest.agentId);
        return STATUS_OK;
    }

    @Override
    public int stopSandbox(SandboxHandle handle, int reason) {
        SandboxRecord rec = find(handle);
        if (rec == null) {
            return STATUS_NOT_FOUND;
        }
        rec.state = STATE_STOPPED;
        rec.paused = false;
        mSandboxes.remove(rec.handle.handleId);
        mConsented.remove(rec.handle.agentId);
        Slog.i(TAG, "sandbox stopped: " + rec.handle.handleId + " reason=" + reason);
        return STATUS_OK;
    }

    @Override
    public int pauseSandbox(SandboxHandle handle, boolean pause) {
        SandboxRecord rec = find(handle);
        if (rec == null) {
            return STATUS_NOT_FOUND;
        }
        rec.paused = pause;
        rec.state = pause ? STATE_PAUSED : STATE_ACTIVE;
        Slog.i(TAG, "sandbox " + (pause ? "paused" : "resumed") + ": " + rec.handle.handleId);
        return STATUS_OK;
    }

    @Override
    public int revokeConsent(SandboxHandle handle) {
        SandboxRecord rec = find(handle);
        if (rec == null) {
            return STATUS_NOT_FOUND;
        }
        mConsented.remove(rec.handle.agentId);
        rec.handle.grantedCapabilities = new String[0];
        rec.state = STATE_STOPPED;
        mSandboxes.remove(rec.handle.handleId);
        Slog.i(TAG, "consent revoked for " + rec.handle.agentId);
        return STATUS_OK;
    }

    @Override
    public SandboxStatus getStatus(SandboxHandle handle) {
        SandboxRecord rec = find(handle);
        return rec != null ? rec.toStatus() : null;
    }

    @Override
    public SandboxStatus[] listActiveSandboxes() {
        List<SandboxStatus> list = new ArrayList<>();
        for (SandboxRecord r : mSandboxes.values()) {
            if (r.state != STATE_STOPPED) {
                list.add(r.toStatus());
            }
        }
        return list.toArray(new SandboxStatus[0]);
    }

    // ---------------------------------------------------------------- 工具
    private SandboxRecord find(SandboxHandle h) {
        if (h == null || h.handleId == null) {
            return null;
        }
        return mSandboxes.get(h.handleId);
    }

    private static void copyHandle(SandboxHandle from, SandboxHandle to) {
        to.handleId = from.handleId;
        to.agentId = from.agentId;
        to.sandboxPid = from.sandboxPid;
        to.sandboxUid = from.sandboxUid;
        to.createdAtMs = from.createdAtMs;
        to.sessionId = from.sessionId;
        to.grantedCapabilities = from.grantedCapabilities;
        to.remainingCalls = from.remainingCalls;
        to.remainingWalltimeMs = from.remainingWalltimeMs;
    }
}
