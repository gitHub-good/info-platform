package com.info.platform.domain.push;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 推送记录实体（push_record 表，§4.2 DDL）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/时间戳/version）由基础设施层 {@code PushRepositoryImpl} 经 {@link
 * #reconstruct} 回填。
 *
 * <h2>幂等防重推</h2>
 *
 * {@link #create} 构造幂等键 {@code idempotencyKey = userId + ":" + pushType.code() + ":" +
 * refId}（§4.4「幂等业务语义键」）， DB {@code UNIQUE(idempotency_key)} 为最后防线。同一异动对同一用户重复消费（@Async 重投/事件重发）→
 * INSERT 冲突 → {@code PushRepository.saveIfAbsent} 返回 empty，应用层跳过，绝不重推。
 *
 * <h2>状态流转</h2>
 *
 * 新建 → {@link PushStatus#PENDING}；SSE 推送在线用户成功 → {@link #markPushed} 翻 {@link PushStatus#SUCCESS} +
 * 回填 pushed_at；推送失败重试 1 次仍失败 → {@link #markFailed} 翻 {@link PushStatus#FAILED}（记 ERROR 告警）。 离线用户留
 * PENDING，重连 SSE 补拉后翻 SUCCESS。状态翻转与 retry_count 由领域行为维护，保持不变量；时间戳由基础设施层落库时回填。
 */
public class PushRecord {

    private Long id;
    private final long userId;
    private final Long subjectId;
    private final PushType pushType;
    private final String refId;
    private final String content;
    private final String idempotencyKey;
    private PushStatus status;
    private Instant pushedAt;
    private int retryCount;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    private PushRecord(
            Long id,
            long userId,
            Long subjectId,
            PushType pushType,
            String refId,
            String content,
            String idempotencyKey,
            PushStatus status,
            Instant pushedAt,
            int retryCount,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.subjectId = subjectId;
        this.pushType = pushType;
        this.refId = refId;
        this.content = content;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.pushedAt = pushedAt;
        this.retryCount = retryCount;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 构建新推送记录（id/时间戳/version 留空，落库后回填；status=PENDING，retryCount=0）。
     *
     * <p>幂等键由本方法据 {@code userId+pushType+refId} 拼装，调用方无需关心拼装规则。
     *
     * @param userId 推送目标用户
     * @param subjectId 关联标的（异动推送必填，其余类型可空）
     * @param pushType 推送类型
     * @param refId 关联事件/简报 id（异动=anomaly_event.id），参与幂等键拼接，可空
     * @param content 推送内容（人读摘要，落库供 history 与 SSE data）
     */
    public static PushRecord create(
            long userId, Long subjectId, PushType pushType, String refId, String content) {
        Objects.requireNonNull(pushType, "pushType 必填");
        Objects.requireNonNull(content, "content 必填");
        String key = userId + ":" + pushType.code() + ":" + (refId == null ? "null" : refId);
        return new PushRecord(
                null,
                userId,
                subjectId,
                pushType,
                refId,
                content,
                key,
                PushStatus.PENDING,
                null,
                0,
                0L,
                null,
                null);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static PushRecord reconstruct(
            Long id,
            long userId,
            Long subjectId,
            PushType pushType,
            String refId,
            String content,
            String idempotencyKey,
            PushStatus status,
            Instant pushedAt,
            int retryCount,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return new PushRecord(
                id,
                userId,
                subjectId,
                pushType,
                refId,
                content,
                idempotencyKey,
                status,
                pushedAt,
                retryCount,
                version,
                createdAt,
                updatedAt);
    }

    /** SSE 推送成功后翻转状态为已推并回填推送时刻（幂等：重复调用安全）。 */
    public void markPushed(Instant pushedAt) {
        this.status = PushStatus.SUCCESS;
        this.pushedAt = Objects.requireNonNull(pushedAt, "pushedAt 必填");
    }

    /** 记一次重试（retry_count +1，状态保持 PENDING；调用方据重试结果再 markPushed/markFailed）。 */
    public void recordRetry() {
        this.retryCount++;
    }

    /** 推送失败（重试 1 次仍失败）翻转状态为失败。 */
    public void markFailed() {
        this.status = PushStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public Optional<Long> getSubjectId() {
        return Optional.ofNullable(subjectId);
    }

    public PushType getPushType() {
        return pushType;
    }

    public Optional<String> getRefId() {
        return Optional.ofNullable(refId);
    }

    public String getContent() {
        return content;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PushStatus getStatus() {
        return status;
    }

    public Optional<Instant> getPushedAt() {
        return Optional.ofNullable(pushedAt);
    }

    public int getRetryCount() {
        return retryCount;
    }

    public long getVersion() {
        return version;
    }

    public Optional<Instant> getCreatedAt() {
        return Optional.ofNullable(createdAt);
    }

    public Optional<Instant> getUpdatedAt() {
        return Optional.ofNullable(updatedAt);
    }
}
