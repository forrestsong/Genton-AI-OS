#!/usr/bin/env python3
import sys

# 1. Fix InferenceSession.tier: remove final
p1 = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative/AgentInferenceRouterService.java'
with open(p1) as f:
    c1 = f.read()

old1 = '''    private static class InferenceSession {
        final String requestId;
        final InferenceRequest request;
        final IInferenceCallback callback;
        final int tier;
        final long startTimeMs;'''
new1 = '''    private static class InferenceSession {
        final String requestId;
        final InferenceRequest request;
        final IInferenceCallback callback;
        int tier;  // non-final: cloud fallback reassigns to local tier
        final long startTimeMs;'''
if old1 not in c1:
    print("ERROR: InferenceSession tier not found")
    sys.exit(1)
c1 = c1.replace(old1, new1, 1)
with open(p1, 'w') as f:
    f.write(c1)
print("PATCHED InferenceSession.tier (removed final) OK")

# 2. Add constants to IAgentInferenceRouter.aidl
p2 = '/root/aosp/frameworks/base/core/java/com/ainative/inference/IAgentInferenceRouter.aidl'
with open(p2) as f:
    c2 = f.read()

old2 = 'interface IAgentInferenceRouter {\n    InferenceCapabilities getCapabilities();'
new2 = '''interface IAgentInferenceRouter {
    // 推理层级（旗舰版本地推理 / 通用版云端，B5.3 双版本原则）
    const int TIER_LOCAL_TINY  = 1;  // 常驻小模型（意图识别/路由）
    const int TIER_LOCAL_LARGE = 2;  // 端侧大模型（NPU 有余量且离线可用）
    const int TIER_CLOUD       = 4;  // 云端推理（需授权+证明+白名单，暂未实现）

    const int STATUS_OK              = 0;
    const int STATUS_DENIED          = 1;
    const int STATUS_NO_ROUTE        = 2;
    const int STATUS_INTERNAL_ERROR  = -1;

    InferenceCapabilities getCapabilities();'''
if old2 not in c2:
    print("ERROR: IAgentInferenceRouter block not found")
    sys.exit(1)
c2 = c2.replace(old2, new2, 1)
with open(p2, 'w') as f:
    f.write(c2)
print("PATCHED IAgentInferenceRouter constants OK")
print("DONE")
