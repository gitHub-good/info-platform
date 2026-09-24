package com.info.platform.domain.common;

import java.time.Instant;
import java.util.List;

/**
 * Job 执行记录仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。写入方 {@link
 * com.info.platform.infrastructure.common.JobExecutionRecorder}；查询方 {@link
 * com.info.platform.application.common.JobLogQueryService}（经接口层 {@code JobLogController}）。
 *
 * <h2>写入</h2>
 *
 * {@link #save}：id 为空走 INSERT 并回填主键/时间戳，非空走 UPDATE（翻转 SUCCESS/FAILED）。
 *
 * <h2>游标分页</h2>
 *
 * {@link #byJobNameCursor} 按 {@code id DESC}（自增主键顺序即 created_at 顺序，newest-first）取页： {@code WHERE
 * job_name=? AND id < cursor LIMIT n}。游标 = 上一页末条 id（最小 id）；返回页满 {@code limit} 条时下一页 游标取该页末条
 * id，否则已到末页。对齐 {@code PushRepository#findByUserIdCursor} 防深分页模式。
 */
public interface JobExecutionLogRepository {

    /**
     * 落库：id 为空 INSERT 回填主键/时间戳，非空 UPDATE（按 id 更新状态翻转）。
     *
     * @return 落库后的记录（含回填 id/createdAt/updatedAt）
     */
    JobExecutionLog save(JobExecutionLog log);

    /**
     * 游标分页查询（newest-first）。
     *
     * @param jobName 过滤；null/空白表示不限 Job（全部）
     * @param cursor 上一页末条 id（最小 id）；null 表首页
     * @param limit 单页条数
     * @return 按 id DESC 排序的本页记录
     */
    List<JobExecutionLog> byJobNameCursor(String jobName, Long cursor, int limit);

    /**
     * 页码模式组合过滤分页（M9 T61）：{@code ORDER BY id DESC LIMIT size OFFSET (page-1)×size}。
     *
     * <p>与游标模式<b>同序同过滤</b>（id 主键全序）；越界页天然空列表（200 + 空列表 + 如实回显，ADR-0035）。 page/size 由接口层 {@code
     * PageQuery} 校验（page≥1、size 1~50），端口不再重复校验。
     *
     * @param filter 组合过滤条件（jobName + status AND 语义）
     * @param page 页码（1 起）
     * @param size 页大小
     */
    List<JobExecutionLog> byFilterPage(JobLogFilter filter, int page, int size);

    /**
     * 页码模式组合过滤精确计数（M9 T61）：与 {@link #byFilterPage} 同一 WHERE（审计场景要精确 total，不做近似）。
     *
     * @param filter 组合过滤条件（与 byFilterPage 同一实例同口径）
     */
    long countByFilter(JobLogFilter filter);

    /**
     * 最近 N 条（不限 Job，newest-first），供默认概览/单测。
     *
     * @param limit 条数
     */
    List<JobExecutionLog> findRecent(int limit);

    /**
     * 统计 {@code created_at} 落 [since, ∞) 的执行总行数（T42 概览任务健康 windowRuns，方案 §4.6 滚动 24h）。
     *
     * <p>窗口按 {@code created_at} 扫描（V15 索引 {@code idx_job_log_time} 落在该列；Recorder 插入时 created_at 与
     * start_time 同刻写入，语义等价，取索引列避免全表扫）。
     */
    long countSince(Instant since);

    /** 统计 {@code created_at} 落 [since, ∞) 的 FAILED 行数（T42 概览任务健康 windowFailed）。 */
    long countFailedSince(Instant since);

    /**
     * {@code created_at} 落 [since, ∞) 的 FAILED 行（newest-first，id DESC），供 T42 概览提取失败涉及的 jobName。
     *
     * <p>仅失败行（个人量级失败为少数），limit 为护栏防异常刷库拖垮概览。
     */
    List<JobExecutionLog> findFailedSince(Instant since, int limit);
}
