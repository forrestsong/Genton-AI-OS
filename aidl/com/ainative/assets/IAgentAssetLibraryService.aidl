package com.ainative.assets;

import com.ainative.assets.AssetManifest;
import com.ainative.assets.AssetDescriptor;
import com.ainative.assets.AssetQuery;
import com.ainative.assets.ResolveRequest;
import com.ainative.assets.ResolvedAsset;
import com.ainative.assets.CompatibilityReport;
import com.ainative.assets.RegressionJob;
import com.ainative.assets.ToolSchema;
import com.ainative.assets.ExecutionReport;
import com.ainative.assets.AssetUpdateInfo;
import com.ainative.assets.IAgentAssetListener;

/**
 * 资产目录服务：管理 Skill/DMI/Macro 三类操作资产的全生命周期
 * 
 * 职责：
 * - 注册/验签/版本绑定/机型兼容性管理
 * - 解析降级链（DMI → Macro → L2）
 * - staged-enable 与回归测试
 * - 失败自动停用与执行回灌统计
 * - 工具Schema注入（供Agent调用）
 * 
 * 红线：
 * - 未签名资产不得分发
 * - Macro只能声明UI注入类能力
 * - 本服务不执行副作用，只提供目录与解析建议
 */
interface IAgentAssetLibraryService {
    const int STATUS_OK              = 0;
    const int STATUS_DENIED          = 1;
    const int STATUS_NOT_FOUND       = 2;   // 无匹配资产（走L2兜底）
    const int STATUS_UNSIGNED        = 3;   // 签名校验失败
    const int STATUS_INCOMPATIBLE    = 4;   // 机型/App版本/OS不兼容
    const int STATUS_DISABLED        = 5;   // 资产被停用
    const int STATUS_INTERNAL_ERROR  = -1;

    // 资产类型
    const int KIND_SKILL = 1;
    const int KIND_DMI   = 2;
    const int KIND_MACRO = 3;

    // 资产状态
    const int ASSET_STATUS_ENABLED  = 0;
    const int ASSET_STATUS_DISABLED = 1;
    const int ASSET_STATUS_STAGED   = 2;    // 待回归验证

    /**
     * 搜索资产目录
     * @param query 查询条件（kind/包名/语义/标签）
     * @return 匹配的资产描述符列表
     */
    AssetDescriptor[] queryAssets(in AssetQuery query);

    /**
     * 获取单个资产详情
     * @param assetId 资产ID
     * @return 资产描述符，不存在返回null
     */
    AssetDescriptor getAsset(in String assetId);

    /**
     * 解析"最合适资产"：给执行中介用
     * 按 intent+目标App+机型+版本 给出首选与降级链
     * 
     * @param request 解析请求（含目标App、机型、版本、优先顺序）
     * @param resolved 输出解析结果（含降级链）
     * @return STATUS_OK 或错误码
     */
    int resolveForUse(in ResolveRequest request, out ResolvedAsset resolved);

    /**
     * 注册/更新资产
     * OTA或开发者渠道，强签名校验；未过回归则仅staged
     * 
     * @param manifest 资产清单
     * @param signedPayload 签名后的载荷
     * @param assetId 输出分配的资产ID（单元素数组，取 assetId[0]）
     * @return STATUS_OK 或错误码
     */
    int registerAsset(in AssetManifest manifest, in byte[] signedPayload,
                      out String[] assetId);

    /**
     * 设置资产状态（启用/停用/staged）
     * 需平台签名权限
     * 
     * @param assetId 资产ID
     * @param status ASSET_STATUS_*
     * @return STATUS_OK 或错误码
     */
    int setAssetStatus(in String assetId, in int status);

    /**
     * 机型回归测试
     * 跑该资产（或本机全部）冒烟，产出通过/失败清单
     * 
     * @param assetId 资产ID，空字符串表示全部启用资产
     * @param job 输出回归任务句柄
     * @return STATUS_OK 或错误码
     */
    int runRegression(in String assetId, out RegressionJob job);

    /**
     * 执行结果回灌
     * 执行中介执行后回调；失败统计 → 自动disable
     * 
     * @param report 执行报告（成败/耗时/失败原因）
     * @return STATUS_OK
     */
    int reportExecution(in ExecutionReport report);

    /**
     * 获取工具描述符列表（供Agent调用）
     * 返回调用方可用的Tool Schema列表（受能力过滤）
     * 
     * @param agentId Agent标识
     * @return 工具Schema列表
     */
    ToolSchema[] getToolDescriptors(in String agentId);

    /**
     * 注册资产变更监听器
     * OTA/停用 → 大脑与上下文缓存失效
     * 
     * @param listener 监听器
     * @return STATUS_OK
     */
    int registerListener(in IAgentAssetListener listener);

    /**
     * 注销监听器
     * @param listener 监听器
     * @return STATUS_OK
     */
    int unregisterListener(in IAgentAssetListener listener);

    /**
     * 获取兼容性报告（诊断用）
     * @param assetId 资产ID
     * @return 兼容性报告
     */
    CompatibilityReport getCompatibilityReport(in String assetId);
}
