# 负样本 fixture：按前缀判定内部动作（已废弃的脆弱实现）

本文件是 `verification/check_1_1_no_prefix_bypass.py` 的**检测器自检负样本**。

## 为什么需要它

一个从未报出过问题的检测器是不可信的：如果它的正则写错、或扫描范围为空，它会对
任何代码都输出 PASS。因此门禁必须先在**一段已知脆弱的代码**上验证自己确实能报出
问题，其后的 PASS 才有意义。

## 负样本内容

以下实现是历史上的真实脆弱写法：内部动作身份由**调用方可控的 actionId 前缀**决定。
这意味着调用方只要把动作名写成 `sys.` / `cmd.` 开头，就能被系统当成内部动作处理，
从而绕过白名单与常规鉴权（见 `verification/README.md` 的 WBS 1.1 说明）。

```java
private boolean isInternalAction(String actionId) {
    return actionId != null && 
           (actionId.startsWith("sys.") || actionId.startsWith("cmd."));
}
```

正确实现应改为**白名单判定**（`InternalActionRegistry`），不依赖调用方可控的字符串前缀。

## 使用方式

门禁脚本按大括号配对提取本文件中的 `isInternalAction` 函数体，并要求其匹配前缀判定
正则 `startsWith("sys."|"cmd."|PREFIX_SYS|PREFIX_CMD)`。若匹配失败，说明检测器失效，
门禁会直接判失败并声明"其后所有 PASS 均不可信"。

> 本 fixture 自包含于仓库，不依赖任何未发布的内部报告。
