package com.info.platform.domain.push;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 推送记录仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 *
 * <h2>幂等防重推</h2>
 *
 * {@link #saveIfAbsent} 是防重推核心：{@code idempotency_key} 已存在 → 返回 empty（跳过不重推）； 否则 INSERT。 DB {@code
 * UNIQUE(idempotency_key)} 为最后防线——并发竞态下 INSERT 冲突也视为已存在返回 empty，绝不重推（对齐 §4.4、§4.3 流程 3）。
 *
 * <h2>history 与重连补拉</h2>
 *
 * {@link #findByUserIdCursor} 游标分页供 history 接口（{@code WHERE id > cursor LIMIT n}，防深分页 §4.4）； {@link
 * #findLatestByUser} 取该用户最近 N 条（id 降序取前 N 再反转为升序），供前端通知面板「最近记录」兜底拉取（P1-1）； {@link
 * #findPendingByUser} 取离线用户待推记录（status=0），供 SSE 重连时按 Last-Event-ID 补拉； {@link #findPending}
 * 取全量待推记录（status=0），供 T15 补推 job 跨用户扫描补推。
 */
public interface PushRepository {

    /**
     * 幂等落库：idempotency_key 已存在则跳过返回 empty；否则 INSERT 并回填主键/时间戳/version。
     *
     * @return 落库后的记录；已存在（防重）或并发 INSERT 冲突时返回 empty
     */
    Optional<PushRecord> saveIfAbsent(PushRecord record);

    /** 更新推送状态（status 翻转/pushed_at/retry_count）；id 非空走 UPDATE，{@code @Version} 乐观锁由基础设施层处理。 */
    void update(PushRecord record);

    /**
     * history 游标分页：按 {@code user_id} + {@code id > cursor} 升序取 {@code limit} 条； cursor 为 null 从头取。
     *
     * @param cursor 上一页最后一条 id（游标），null 表示首页
     * @param type 推送类型过滤，null 表示不限类型
     * @param limit 单页条数（接口层约束，如 20）
     */
    List<PushRecord> findByUserIdCursor(long userId, Long cursor, PushType type, int limit);

    /**
     * 最近 N 条记录（P1-1 前端通知面板兜底）：按 {@code user_id} 取 id 最大的 {@code limit} 条，返回按 id 升序。
     *
     * <p>与 {@link #findByUserIdCursor} 的区别：游标分页从头（最旧）向后翻页，无法一步取到最近记录；本方法一次取尾部（最新）
     * 供「面板打开补全近期记录」单次拉取。
     *
     * @param type 推送类型过滤，null 表示不限类型
     * @param limit 返回条数上限（接口层约束，如 20）
     */
    List<PushRecord> findLatestByUser(long userId, PushType type, int limit);

    /**
     * 离线用户待推记录（status=0/PENDING），按 id 升序，供 SSE 重连补拉。
     *
     * <p><b>保留期过滤（系统体检 20260924 P1-1 批 1 遗留项）</b>：对齐 {@link #findPending} 的 {@code
     * push.retry.pending-retention-days} 语义——{@code created_at < createdSince} 的超期 PENDING
     * 不再补推（前端长期未上线期间 的存量积压止血），防 SSE 重连时一次性补拉全量积压。
     *
     * @param createdSince 创建时间下限（通常 now - 保留期）
     */
    List<PushRecord> findPendingByUser(long userId, Instant createdSince);

    /**
     * 待推记录（status=0/PENDING），按 id 升序，供 T15 补推 job 跨用户扫描补推。
     *
     * <p>与 {@link #findPendingByUser} 区别：本方法不限 user，扫表全部 status=0 记录；补推 job 据在线状态决定补推/跳过。
     *
     * <p><b>扫描有界（系统体检 20260924 P1-1 后端半段）</b>：{@code LIMIT limit} 防无界全量拉取；{@code created_at >=
     * createdSince} 过期截止——超期 PENDING 直接跳过不再扫（前端未接 SSE 期间积压的待推无消费者，超保留期即放弃补推）， 防 30s 轮询永久全量扫描失控表。
     *
     * @param limit 单轮扫描上限（id 升序取前 N，先来先补推）
     * @param createdSince 创建时间下限（早于此的 PENDING 不再入选，通常 now - 保留期）
     */
    List<PushRecord> findPending(int limit, Instant createdSince);
}
