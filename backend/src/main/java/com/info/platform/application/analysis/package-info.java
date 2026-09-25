/**
 * 分析管道应用层（M15，ADR-0046）：批窗口编排（L0 预筛 → L1 批量归类 → L2 事件提取[后续批]）、LLM 批量调用与拆批、标的回联、 管道状态面。 {@code
 * NewsPipelineJob}（ManagedJob 第 9 键）为唯一 tick 入口；测试 profile 种子停用零注册。
 */
package com.info.platform.application.analysis;
