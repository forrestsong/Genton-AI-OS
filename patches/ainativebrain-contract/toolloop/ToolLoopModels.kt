package com.ainative.brain.toolloop

// [主线2 修复] 本文件原有 9 个类型声明（ActionPlan / ActionExecutionResult /
// GoalExecutionResult / GoalExecutionStatus / ExecutionContext / ExecutionResult /
// BudgetUsage / CapabilityTokenInfo / AssertionResult），与 ToolLoopDataTypes.kt
// 中的同名类型重复（redeclaration），导致 toolloop 包 19 处编译错误。
//
// 实测依据：ExecutionResult 的构造调用使用 completedGoals / budgetUsage
// （ToolLoopDataTypes 版独有，外部引用分别 10 / 22 次），budgetUsage/toJson
// 亦被大量使用；ToolLoopDataTypes.kt 为有效版本。
// 原文件备份于 ToolLoopModels.kt.obsolete。
