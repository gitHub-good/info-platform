package com.info.platform.domain.common;

/**
 * Job 执行日志组合过滤条件（M9 页码模式，纯 JDK record）。
 *
 * <p>页码模式查询条件载体：{@code jobName + status} 任意组合、全部 AND 语义；{@code byFilterPage}/{@code countByFilter}
 * 同一 filter 保证页数据与计数同口径。
 *
 * @param jobName 过滤；null/blank 不过滤（命中 idx_job_log_name_time 前缀）
 * @param status 状态过滤；null 不过滤（等值匹配持久化名 STARTED/SUCCESS/FAILED，无索引全扫毫秒级——ADR-0035）
 */
public record JobLogFilter(String jobName, JobExecutionStatus status) {}
