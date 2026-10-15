package com.ainative.perception;

import com.ainative.perception.ScreenFrame;
import com.ainative.perception.CaptureRequest;
import com.ainative.perception.ScreenStreamConfig;
import com.ainative.perception.ScreenStreamHandle;
import com.ainative.perception.IAgentFrameListener;

/**
 * IAgentScreenService
 * AI Native OS 系统服务接口
 * 自动生成 - 请勿手动编辑
 */
interface IAgentScreenService {

    ScreenFrame captureScreen(in CaptureRequest request);

    ScreenStreamHandle startScreenStream(in ScreenStreamConfig config, in IAgentFrameListener listener);

    void stopScreenStream(in ScreenStreamHandle handle);

}