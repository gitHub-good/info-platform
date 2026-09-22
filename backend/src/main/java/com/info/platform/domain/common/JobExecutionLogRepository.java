package com.info.platform.domain.common;

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
     * 最近 N 条（不限 Job，newest-first），供默认概览/单测。
     *
     * @param limit 条数
     */
    List<JobExecutionLog> findRecent(int limit);
}
