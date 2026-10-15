package com.genton.perception;

import com.genton.perception.ScreenFrame;
import com.genton.perception.CaptureRequest;
import com.genton.perception.ScreenStreamConfig;
import com.genton.perception.ScreenStreamHandle;
import com.genton.perception.IAgentFrameListener;

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