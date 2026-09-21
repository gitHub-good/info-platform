package com.info.platform.domain.ai;

import java.util.Optional;

/**
 * AI 简报仓储端口（依赖倒置：领域层定义、基础设施层 {@code AiBriefRepositoryImpl} 实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。对齐技术方案 §4.3 流程 2 + §4.4「AI 简报状态用 status+乐观锁」。
 *
 * <h2>读取端口</h2>
 *
 * <ul>
 *   <li>{@link #findById}：GET /ai-briefs/{taskId} 查询，返回任务（含 status/content/sourceLinks）。
 *   <li>{@link #findByIdempotencyKey}：POST
 *       幂等查重（idempotency_key=subjectId+briefType+yyyyMMdd），已完成直返上次结果。
 * </ul>
 *
 * <h2>写端口</h2>
 *
 * <ul>
 *   <li>{@link #save}：INSERT 新任务（id 空，{@code status=0} 受理）或 UPDATE 终态（id 非空，{@code @Version} 乐观锁守护
 *       updateById）。
 *   <li>{@link #claim}：CAS 领取——{@code UPDATE SET version=version+1, updated_at=? WHERE id=? AND
 *       status=0 AND version=?}，影响 1 行→重选回读返回（version 已 bump）；影响 0 行→返回 empty（被并发领走或已终态，跳过）。
 * </ul>
 *
 * <p>{@code claim} 与 {@code save} 协同：claim 成功后 worker 持有 bumped version 的实体，终态 {@code
 * save(updateById)} 以该 version 作 WHERE 条件，bump 至 version+1，防并发覆写（单写者 @Async 模型下双发幂等已由
 * UNIQUE(idempotency_key) 拦，CAS 是防御网）。
 */
public interface AiBriefRepository {

    /** INSERT 新任务（id 空）或 UPDATE（id 非空，乐观锁）；返回回填 id/version/时间戳的实体。 */
    AiBrief save(AiBrief brief);

    /** 按 taskId 查询（GET 端点）。 */
    Optional<AiBrief> findById(Long id);

    /** 按幂等键查询（POST 查重，已完成直返上次结果）。 */
    Optional<AiBrief> findByIdempotencyKey(String idempotencyKey);

    /**
     * CAS 领取任务（异步 Worker）：{@code WHERE id=? AND status=0 AND version=?} bump version。
     *
     * @param id 任务 id
     * @param expectedVersion 期望的当前 version（findById 读得）
     * @return 领取成功→bumped version 的回读实体；影响 0 行（被并发领走 / 已终态）→{@link Optional#empty()}
     */
    Optional<AiBrief> claim(Long id, long expectedVersion);
}
