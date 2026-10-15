package com.genton.brain.planner

// [主线2 修复] 本文件原有 GoalNode / GoalStatus / GoalDAG 三个类型声明，
// 与 PlanningDataTypes.kt 中的同名类型重复（redeclaration），导致 planner 包
// 26 处编译错误及 Planner.kt 大量「找不到命名参数」错误。
//
// 实测依据：
//   - 本文件版本独有的成员（GoalNode.actions / GoalDAG.edges / GoalStatus.PENDING）
//     在整个代码库中无任何引用；
//   - PlanningDataTypes.kt 版本独有的成员（GoalNode.parentGoalId、depth，
//     GoalDAG.rootGoalId、turnId，GoalStatus.PLANNED/SUPERSEDED）被 Planner.kt 等大量使用。
//
// 故保留 PlanningDataTypes.kt 的定义，此处不再声明。原文件备份于 GoalModels.kt.obsolete。
