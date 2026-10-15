// IAgentMacroRecorderService.aidl
package com.genton.macro;

import com.genton.macro.MacroRecordSession;
import com.genton.macro.MacroScript;
import com.genton.macro.MacroStep;
import com.genton.macro.IMacroRecordListener;

/**
 * 宏录制服务接口
 * 
 * 职责：
 * 1. 录制用户操作序列
 * 2. 生成可重放的宏脚本
 * 3. 重放校验器
 * 4. 绑定到 ActionCardData
 * 
 * 对应任务：F1.1 宏录制与生成工具
 * 草案依据：D11 + F类功能扩展
 */
interface IAgentMacroRecorderService {
    const int STATUS_OK = 0;
    const int STATUS_DENIED = 1;
    const int STATUS_SESSION_NOT_FOUND = 2;
    const int STATUS_INVALID_MACRO = 3;
    const int STATUS_REPLAY_FAILED = 4;
    const int STATUS_INTERNAL_ERROR = -1;
    
    /**
     * 开始录制宏
     * 
     * @param intentAction 意图动作（可选）
     * @param sessionId 输出参数，返回会话 ID（单元素数组，取 sessionId[0]）
     * @return STATUS_OK 成功；其他错误码
     */
    int startRecording(in String intentAction, out String[] sessionId);
    
    /**
     * 停止录制
     * 
     * @param sessionId 会话 ID
     * @return STATUS_OK 成功；其他错误码
     */
    int stopRecording(in String sessionId);
    
    /**
     * 获取录制会话信息
     * 
     * @param sessionId 会话 ID
     * @return MacroRecordSession，不存在返回 null
     */
    MacroRecordSession getSession(in String sessionId);
    
    /**
     * 生成宏脚本（从录制会话）
     * 
     * @param sessionId 会话 ID
     * @param macroName 宏名称
     * @param macroDescription 宏描述
     * @param macroId 输出参数，返回生成的宏 ID（单元素数组，取 macroId[0]）
     * @return STATUS_OK 成功；其他错误码
     */
    int generateMacro(in String sessionId, in String macroName, in String macroDescription, out String[] macroId);
    
    /**
     * 获取宏脚本
     * 
     * @param macroId 宏 ID
     * @return MacroScript，不存在返回 null
     */
    MacroScript getMacro(in String macroId);
    
    /**
     * 查询所有宏
     * 
     * @param intentAction 按意图过滤（null = 全部）
     * @return MacroScript 数组
     */
    MacroScript[] queryMacros(in String intentAction);
    
    /**
     * 重放宏（用于校验）
     * 
     * @param macroId 宏 ID
     * @param dryRun 是否模拟执行（不实际操作）
     * @return STATUS_OK 成功；STATUS_REPLAY_FAILED 失败
     */
    int replayMacro(in String macroId, boolean dryRun);
    
    /**
     * 删除宏
     * 
     * @param macroId 宏 ID
     * @return STATUS_OK 成功；其他错误码
     */
    int deleteMacro(in String macroId);
    
    /**
     * 订阅录制事件
     * 
     * @param listener 监听器
     */
    void subscribeRecordEvents(in IMacroRecordListener listener);
    
    /**
     * 取消订阅
     * 
     * @param listener 监听器
     */
    void unsubscribeRecordEvents(in IMacroRecordListener listener);
    
    /**
     * 导出宏脚本（JSON 格式）
     * 
     * @param macroId 宏 ID
     * @return JSON 字符串，失败返回 null
     */
    String exportMacro(in String macroId);
    
    /**
     * 导入宏脚本（JSON 格式）
     * 
     * @param macroJson JSON 字符串
     * @param macroId 输出参数，返回导入的宏 ID（单元素数组，取 macroId[0]）
     * @return STATUS_OK 成功；其他错误码
     */
    int importMacro(in String macroJson, out String[] macroId);
}
