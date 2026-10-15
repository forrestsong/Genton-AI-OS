// IMacroRecorderService.aidl
package com.ainative.macro;

import com.ainative.macro.MacroRecording;
import com.ainative.macro.MacroScript;
import com.ainative.macro.MacroExecutionResult;
import com.ainative.macro.IMacroRecordingCallback;

/**
 * 宏录制服务接口
 * 
 * 职责：
 * 1. 录制用户交互（点击、滑动、输入等）
 * 2. 生成可执行的宏脚本
 * 3. 重放与校验
 * 4. 绑定 ActionCardData 与审计
 * 
 * 实现方：system_server 平台框架
 * 调用方：宏录制工具、Agent 大脑、资产目录服务
 * 
 * 对应任务：F1.1 宏录制工具链
 * 前置依赖：A2.1 感知聚合器, A3.2 审计服务
 */
interface IMacroRecorderService {
    // 状态码
    const int STATUS_OK = 0;
    const int STATUS_DENIED = 1;              // 权限拒绝
    const int STATUS_RECORDING_ACTIVE = 2;    // 已有活跃录制
    const int STATUS_NOT_RECORDING = 3;       // 未在录制
    const int STATUS_INVALID_SCRIPT = 4;      // 脚本无效
    const int STATUS_EXECUTION_FAILED = 5;    // 执行失败
    const int STATUS_INTERNAL_ERROR = -1;
    
    /**
     * 开始录制宏
     * 
     * @param name 宏名称
     * @param targetPackage 目标应用包名（null = 不限定）
     * @param recordingId 输出参数，返回录制 ID（单元素数组，取 recordingId[0]）
     * @return STATUS_OK 成功；其他错误码
     */
    int startRecording(in String name, in @nullable String targetPackage, out String[] recordingId);
    
    /**
     * 停止录制
     * 
     * @param recordingId 录制 ID
     * @param recording 输出参数，返回录制数据
     * @return STATUS_OK 成功；其他错误码
     */
    int stopRecording(in String recordingId, out MacroRecording recording);
    
    /**
     * 取消录制
     * 
     * @param recordingId 录制 ID
     * @return STATUS_OK 成功；其他错误码
     */
    int cancelRecording(in String recordingId);
    
    /**
     * 生成宏脚本
     * 
     * @param recording 录制数据
     * @param perceptionStrategy 感知策略（0=none, 1=hybrid, 2=perception_only）
     * @param script 输出参数，返回生成的脚本
     * @return STATUS_OK 成功；其他错误码
     */
    int generateScript(in MacroRecording recording, int perceptionStrategy, out MacroScript script);
    
    /**
     * 保存脚本到资产库
     * 
     * @param script 宏脚本
     * @param assetId 输出参数，返回资产 ID（单元素数组，取 assetId[0]）
     * @return STATUS_OK 成功；其他错误码
     */
    int saveScriptAsAsset(in MacroScript script, out String[] assetId);
    
    /**
     * 执行宏脚本（重放）
     * 
     * @param scriptId 脚本 ID 或资产 ID
     * @param sessionId 执行会话 ID（关联 Agent Turn）
     * @param callback 执行回调（可选）
     * @param executionId 输出参数，返回执行 ID（单元素数组，取 executionId[0]）
     * @return STATUS_OK 成功；其他错误码
     */
    int executeScript(in String scriptId, in String sessionId, in @nullable IMacroRecordingCallback callback, out String[] executionId);
    
    /**
     * 获取执行结果
     * 
     * @param executionId 执行 ID
     * @return MacroExecutionResult，不存在返回 null
     */
    @nullable MacroExecutionResult getExecutionResult(in String executionId);
    
    /**
     * 校验脚本（干跑，不执行实际动作）
     * 
     * @param scriptId 脚本 ID
     * @return 校验结果（JSON 字符串）
     */
    String validateScript(in String scriptId);
    
    /**
     * 获取当前录制状态
     * 
     * @return 录制 ID，未在录制返回 null
     */
    @nullable String getCurrentRecording();
    
    /**
     * 获取脚本列表
     * 
     * @param targetPackage 目标包名（null = 全部）
     * @return MacroScript 数组
     */
    MacroScript[] listScripts(in @nullable String targetPackage);
    
    /**
     * 删除脚本
     * 
     * @param scriptId 脚本 ID
     * @return STATUS_OK 成功；其他错误码
     */
    int deleteScript(in String scriptId);
}
