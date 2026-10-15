// IAgentSandboxManager.aidl
// 沙箱管理器接口 - 由系统服务实现，大脑/SystemUI 调用
package com.genton.sandbox;

import com.genton.sandbox.SandboxManifest;
import com.genton.sandbox.SandboxHandle;
import com.genton.sandbox.SandboxStatus;

/**
 * Agent 沙箱管理器接口
 * 
 * 实现方：系统服务（大脑特权 App 内）
 * 调用方：SystemUI、大脑内部模块（平台签名）
 * 
 * 核心职责：
 * 1. 启动/停止沙箱进程（isolatedProcess）
 * 2. 校验 SandboxManifest（签名+能力声明）
 * 3. 用户授权记录管理
 * 4. 沙箱状态查询与生命周期管理
 * 5. 一键撤销授权（deny 默认）
 * 
 * 隔离保证：
 * - isolatedProcess + 独立 UID
 * - 独立加密存储
 * - netd 网络白名单
 * - CPU/内存 cgroup 配额
 * - SE 域收紧（neverallow）
 * 
 * 红线约束：
 * - 第三方 Agent 无任何 signature|privileged 权限
 * - 能力逐项授权，会话级 TTL
 * - 所有动作仍过策略引擎（含确认）
 */
interface IAgentSandboxManager {
    // 状态码
    const int STATUS_OK              = 0;
    const int STATUS_DENIED          = 1;   // 签名/白名单校验失败
    const int STATUS_UNSIGNED        = 2;   // 清单未签名或签名无效
    const int STATUS_OVER_QUOTA      = 3;   // 超出资源配额
    const int STATUS_NOT_FOUND       = 4;   // 沙箱句柄无效
    const int STATUS_INTERNAL_ERROR  = -1;
    
    /**
     * 启动沙箱
     * 
     * 流程：
     * 1. 校验 SandboxManifest 签名
     * 2. 检查用户授权记录（首启需逐项确认）
     * 3. 创建 isolatedProcess（独立 UID）
     * 4. 配置网络白名单（netd）
     * 5. 设置 cgroup 资源配额
     * 6. 绑定沙箱进程服务
     * 7. 返回 SandboxHandle
     * 
     * @param manifest 沙箱清单（能力声明、签名引用）
     * @param handle 输出参数，返回沙箱句柄
     * @return STATUS_OK 成功；STATUS_UNSIGNED 签名校验失败；
     *         STATUS_DENIED 用户拒绝授权；STATUS_OVER_QUOTA 资源不足
     */
    int startSandbox(in SandboxManifest manifest, out SandboxHandle handle);
    
    /**
     * 停止沙箱
     * 
     * @param handle 沙箱句柄
     * @param reason 停止原因（0=用户主动，1=超时，2=资源回收，3=崩溃）
     * @return STATUS_OK 成功；STATUS_NOT_FOUND 句柄无效
     */
    int stopSandbox(in SandboxHandle handle, int reason);
    
    /**
     * 暂停/恢复沙箱
     * 
     * 场景：免打扰模式、低电量、后台限制
     * 
     * @param handle 沙箱句柄
     * @param pause true=暂停（冻结进程），false=恢复
     * @return STATUS_OK 成功
     */
    int pauseSandbox(in SandboxHandle handle, boolean pause);
    
    /**
     * 撤销授权（一键停用）
     * 
     * 用户可随时撤销已授予的全部能力，沙箱无法再执行受保护操作。
     * 
     * @param handle 沙箱句柄
     * @return STATUS_OK 成功
     */
    int revokeConsent(in SandboxHandle handle);
    
    /**
     * 查询沙箱状态
     * 
     * @param handle 沙箱句柄
     * @return 沙箱状态（进程/资源/授权/审计统计）
     */
    SandboxStatus getStatus(in SandboxHandle handle);
    
    /**
     * 列出活跃沙箱
     * 
     * 用于隐私仪表盘视图
     * 
     * @return 所有活跃沙箱的状态列表
     */
    SandboxStatus[] listActiveSandboxes();
}
