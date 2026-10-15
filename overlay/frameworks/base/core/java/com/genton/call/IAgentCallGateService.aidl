// IAgentCallGateService.aidl
package com.genton.call;

import com.genton.call.CallDescriptor;
import com.genton.call.CallVerdict;
import com.genton.call.WhitelistEntry;
import com.genton.call.GateMode;
import com.genton.call.CallSessionHandle;
import com.genton.call.IAgentCallGateListener;

/**
 * CallGate 通话门控服务（A10.1）
 * 
 * 职责：
 * - 来电判定：白名单检查 + 策略引擎路由（≤50ms）
 * - 白名单管理：联系人/号段/紧急号码（系统保底）
 * - 接管模式管理：L1/L2/L3 授权状态
 * - AI 接管会话：音频桥接 + 本地 ASR/TTS + 摘要
 * 
 * 红线：
 * - 紧急号码/系统号码永不可拦（策略不可覆盖）
 * - 音频本地处理，不落记忆/云，录音默认不存
 * - L1/L2 模式默认关闭，需用户授权，每通审计
 * - 判定必须 ≤50ms，超时回退原生响铃（fail-safe）
 * 
 * @see D16 轨道A-CallGate通话门接口草案.md
 * @see D10 轨道A-策略引擎接口草案.md (call_gate 域)
 */
interface IAgentCallGateService {
    // ==================== 状态码 ====================
    const int STATUS_OK                 = 0;
    const int STATUS_DENIED             = 1;   // 平台签名校验失败
    const int STATUS_RULE_MISS          = 2;   // 无匹配规则 → 调用方须回退原生
    const int STATUS_SESSION_BUSY       = 3;   // 已有接管会话在途
    const int STATUS_NOT_ENABLED        = 4;   // 接管模式未开启（L1/L2 默认关）
    const int STATUS_INTERNAL_ERROR     = -1;

    // ==================== 路由常量 ====================
    const int ROUTE_RING_NATIVE   = 0;   // 原生响铃
    const int ROUTE_AGENT_HANDLE  = 1;   // AI 接管（handleMode 细分 L1/L2）
    const int ROUTE_RECORD_ONLY   = 2;   // 仅记录（L3）

    // ==================== 接管模式 ====================
    const int HANDLE_MODE_L1_SILENT_AI    = 1;  // 静默接起 + 留言转文字
    const int HANDLE_MODE_L2_AUDIO_DIALOG = 2;  // AI 语音对话
    const int HANDLE_MODE_L3_RECORD_ONLY  = 3;  // 仅记录（不接起）

    /**
     * 来电判定（telecom 在响铃前调用，≤50ms）
     * 
     * 流程：
     * 1. 白名单检查（联系人/紧急号码 → RING_NATIVE）
     * 2. 策略引擎 call_gate 域路由
     * 3. 模式授权检查（L1/L2 需授权）
     * 4. 超时/异常回退原生响铃（fail-safe）
     * 
     * @param descriptor 来电描述符（号码/来电显示/时间）
     * @param verdict [out] 判定结果（route/handleMode/ruleId）
     * @return STATUS_OK=成功, STATUS_RULE_MISS=无匹配规则（回退原生）
     */
    int classifyIncoming(in CallDescriptor descriptor, out CallVerdict verdict);

    /**
     * 白名单查询（联系人/号段/实体）
     * 
     * @param numberE164 E.164 格式号码（如 +8613800138000）
     * @return true=白名单（原生响铃），false=非白名单
     */
    boolean isWhitelisted(in String numberE164);

    /**
     * 添加白名单条目
     * 
     * @param entry 白名单条目（号码/号段/联系人ID）
     * @return STATUS_OK=成功, STATUS_DENIED=紧急号码不可删/无权限
     */
    int addWhitelistEntry(in WhitelistEntry entry);

    /**
     * 移除白名单条目
     * 
     * @param numberE164 E.164 格式号码
     * @return STATUS_OK=成功, STATUS_DENIED=系统保护条目不可删
     */
    int removeWhitelistEntry(in String numberE164);

    /**
     * 获取所有白名单条目
     * 
     * @return 白名单列表
     */
    List<WhitelistEntry> listWhitelist();

    /**
     * 获取当前接管模式配置
     * 
     * @return 接管模式（mode/callerScope/greetingTextRef）
     */
    GateMode getGateMode();

    /**
     * 设置接管模式（需用户授权）
     * 
     * @param mode 接管模式配置
     * @return STATUS_OK=成功, STATUS_DENIED=未授权
     */
    int setGateMode(in GateMode mode);

    /**
     * 开始 AI 接管会话（telecom 接起后调用）
     * 
     * @param descriptor 来电描述符
     * @param verdict 判定结果（含 handleMode）
     * @param listener 会话状态监听器（SystemUI/大脑）
     * @param session [out] 会话句柄
     * @return STATUS_OK=成功, STATUS_SESSION_BUSY=已有会话在途
     */
    int startHandleSession(
        in CallDescriptor descriptor,
        in CallVerdict verdict,
        in IAgentCallGateListener listener,
        out CallSessionHandle session
    );

    /**
     * 结束接管会话
     * 
     * @param session 会话句柄
     * @return STATUS_OK=成功
     */
    int endHandleSession(in CallSessionHandle session);

    /**
     * 获取会话状态
     * 
     * @param session 会话句柄
     * @return 0=bridging, 1=greeting, 2=transcribing, 3=done, 4=error, -1=not_found
     */
    int getSessionState(in CallSessionHandle session);
}
