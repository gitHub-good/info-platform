package com.info.platform.domain.ai;

import java.time.Instant;
import java.util.List;

/**
 * 阅读行为留痕仓储端口（依赖倒置：领域层定义、基础设施层实现，T29）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。写入方为应用层 {@code ReadingEventService}（埋点受理 + 窗口去重）；查询方为应用层
 * {@code RecommendationPersonalizer}（阅读画像装配）。
 */
public interface ReadingEventRepository {

    /**
     * 落库（追加型流水，仅 INSERT）：回填 id/createdAt 并返回落库后的实体。
     *
     * @param event 已通过 {@link ReadingEvent#record} 校验的留痕
     * @return 含回填 id/createdAt 的记录
     */
    ReadingEvent save(ReadingEvent event);

    /**
     * 同 user + contentType + contentRef 在 {@code since} 之后是否已有留痕（去重窗口检查）。
     *
     * @param userId 归属用户
     * @param contentType 内容类型
     * @param contentRef 内容引用
     * @param since 窗口起点（含）
     */
    boolean existsSince(
            long userId, ReadingEventType contentType, String contentRef, Instant since);

    /**
     * 查询用户 {@code since} 之后的阅读留痕（newest-first，id DESC）。
     *
     * <p>画像装配取数：近 30 天窗口，个人量级行数有限，{@code limit} 为上限护栏。
     *
     * @param userId 归属用户（行级 {@code WHERE user_id=?}）
     * @param since 窗口起点（含）；null 表示不限
     * @param limit 返回条数上限
     */
    List<ReadingEvent> findByUserSince(long userId, Instant since, int limit);
}
