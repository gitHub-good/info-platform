/**
 * 留痕数据生命周期治理的应用层编排骨架（M10，REQ-20260925-07 / ADR-0036）：{@link
 * com.info.platform.application.retention.RetentionWindows} 窗口视图（每轮现读 + 字段级回退）、{@link
 * com.info.platform.application.retention.RetentionPolicy} 执行参数（批大小 500）、{@link
 * com.info.platform.application.retention.RetentionCleanupService} 逐表分批循环清理（单表失败续跑 + 统计）、
 * {@link com.info.platform.application.retention.RetentionCleanupJob} 第 7 个收编任务（ManagedJob + JobRunStats）。
 */
package com.info.platform.application.retention;
