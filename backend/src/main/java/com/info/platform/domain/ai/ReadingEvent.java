package com.info.platform.domain.ai;

import java.time.Instant;
import java.util.Objects;

/**
 * 阅读行为留痕实体（reading_event 表，T29 推荐相关性优化）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型（含同层 {@link ReadingEventType}），可脱离容器单测、可移植。 追加型流水（只 INSERT 不
 * UPDATE），id/时间戳由基础设施层 {@code ReadingEventRepositoryImpl} 落库时回填。写入方为应用层 {@code
 * ReadingEventService}（埋点受理 + 窗口去重）； 查询方为应用层 {@code RecommendationPersonalizer}（近 30 天阅读画像 →
 * 每日推荐个性化上下文与相关性评分）。
 *
 * <p>幂等友好：无业务唯一键约束（同一阅读可多次留痕），去重由服务层按 「同 user + contentType + contentRef 且 1 小时窗口内已存在」跳过——防前端重复上报与
 * React StrictMode 双触发，窗口外重复阅读照常留痕（阅读次数是画像热度输入）。
 *
 * <p>{@code subjectId} 可空：埋点侧经标的代码/ID 解析，解析不到（如政策阅读、标的已删）记 null， 画像侧按 subjectId
 * 非空聚合（无标的关联的阅读不参与已读标的热度）。
 */
public class ReadingEvent {

    /** content_ref 长度上限（防异常载荷撑爆 TEXT 列与索引）。 */
    static final int CONTENT_REF_MAX_LENGTH = 200;

    private Long id;
    private final long userId;
    private final Long subjectId;
    private final ReadingEventType contentType;
    private final String contentRef;
    private Instant createdAt;

    private ReadingEvent(
            Long id,
            long userId,
            Long subjectId,
            ReadingEventType contentType,
            String contentRef,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.subjectId = subjectId;
        this.contentType = contentType;
        this.contentRef = contentRef;
        this.createdAt = createdAt;
    }

    /**
     * 构建新阅读留痕（id/createdAt 留空，落库后回填）。
     *
     * @param userId 归属用户（行级权限键）
     * @param subjectId 关联标的 id；无标的关联（政策/每日推荐阅读或解析失败）传 null
     * @param contentType 内容类型（必填）
     * @param contentRef 内容引用（必填、长度 ≤200）
     * @throws IllegalArgumentException contentType 缺失 / contentRef 空或超长
     */
    public static ReadingEvent record(
            long userId, Long subjectId, ReadingEventType contentType, String contentRef) {
        Objects.requireNonNull(contentType, "contentType 必填");
        if (contentRef == null || contentRef.isBlank()) {
            throw new IllegalArgumentException("contentRef 不能为空");
        }
        String ref = contentRef.trim();
        if (ref.length() > CONTENT_REF_MAX_LENGTH) {
            throw new IllegalArgumentException("contentRef 超长（>" + CONTENT_REF_MAX_LENGTH + "）");
        }
        return new ReadingEvent(null, userId, subjectId, contentType, ref, null);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static ReadingEvent reconstruct(
            Long id,
            long userId,
            Long subjectId,
            ReadingEventType contentType,
            String contentRef,
            Instant createdAt) {
        return new ReadingEvent(id, userId, subjectId, contentType, contentRef, createdAt);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public ReadingEventType getContentType() {
        return contentType;
    }

    public String getContentRef() {
        return contentRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
