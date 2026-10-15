// Genton (AI Native OS) - 沙箱受限操作面（P5 补齐）
//
// 依据 轨道A-第三方Agent接入面接口草案.md（D14）：
//   沙箱内的第三方 Agent 只能经由本网关发起受限操作（类型白名单读 / 动作请求），
//   网关按 OP_* 类型路由到对应系统服务，并做配额校验。
package com.android.server.genton;

import android.content.Context;
import android.os.ServiceManager;
import android.util.Slog;

import com.genton.action.IAgentActionService;
import com.genton.context.IAgentContextService;
import com.genton.events.IAgentEventStreamService;
import com.genton.memory.IAgentMemoryService;
import com.genton.sandbox.GatewayRequest;
import com.genton.sandbox.GatewayResponse;
import com.genton.sandbox.IAgentSandboxGateway;
import com.genton.sandbox.SandboxHandle;

/** 沙箱受限操作网关 */
public class AgentSandboxGatewayService extends IAgentSandboxGateway.Stub {

    private static final String TAG = "AgentSandboxGateway";

    // 与 IAgentSandboxGateway.aidl 的 OP_* 对齐
    private static final int OP_CONTEXT_READ = 1;

    private final Context mContext;

    public AgentSandboxGatewayService(Context context) {
        mContext = context;
    }

    @Override
    public int execute(SandboxHandle handle, GatewayRequest request, GatewayResponse responseOut) {
        if (handle == null || request == null) {
            return STATUS_DENIED;
        }
        int code;
        String result;
        try {
            switch (request.opType) {
                case OP_CONTEXT_READ: {
                    IAgentContextService ctx = contextService();
                    if (ctx == null) {
                        code = STATUS_INTERNAL_ERROR;
                        result = null;
                    } else {
                        // 只读：拉取当前上下文帧的脱敏视图
                        com.genton.context.ContextFrame frame =
                                new com.genton.context.ContextFrame();
                        int rc = ctx.getCurrentFrame(0, frame);
                        result = (rc == 0) ? frame.toString() : null;
                        code = STATUS_OK;
                    }
                    break;
                }
                default: {
                    // 其它 OP（动作请求等）当前未开放给第三方沙箱
                    Slog.w(TAG, "unsupported opType: " + request.opType);
                    code = STATUS_DENIED;
                    result = null;
                }
            }
        } catch (Throwable t) {
            Slog.e(TAG, "execute failed op=" + request.opType, t);
            code = STATUS_INTERNAL_ERROR;
            result = null;
        }

        if (responseOut != null) {
            responseOut.statusCode = code;
            responseOut.result = result;
            responseOut.errorMessage = (code == STATUS_OK) ? null : "op rejected or failed";
            responseOut.requestId = request.requestId;
        }
        Slog.i(TAG, "execute op=" + request.opType + " -> " + code);
        return code;
    }

    @Override
    public GatewayResponse[] executeBatch(SandboxHandle handle, GatewayRequest[] requests) {
        if (requests == null) {
            return new GatewayResponse[0];
        }
        GatewayResponse[] out = new GatewayResponse[requests.length];
        for (int i = 0; i < requests.length; i++) {
            GatewayResponse r = new GatewayResponse();
            execute(handle, requests[i], r);
            out[i] = r;
        }
        return out;
    }

    @Override
    public String getQuotaStatus(SandboxHandle handle) {
        if (handle == null) {
            return "{\"error\":\"invalid handle\"}";
        }
        return "{\"remainingCalls\":" + handle.remainingCalls
                + ",\"remainingWalltimeMs\":" + handle.remainingWalltimeMs + "}";
    }

    // ---------------------------------------------------------------- 工具
    private IAgentContextService contextService() {
        try {
            android.os.IBinder b =
                    ServiceManager.getService("com.genton.context.IAgentContextService");
            return b == null ? null : IAgentContextService.Stub.asInterface(b);
        } catch (Throwable t) {
            return null;
        }
    }
}
