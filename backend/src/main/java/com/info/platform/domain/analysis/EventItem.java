package com.info.platform.domain.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 结构化事件实体（{@code event_item} 表，M15 T122，ADR-0046 裁决 1；一条资讯至多一条事件 v1——UNIQUE(news_id)，多事件留 M17）。
 *
 * <p>领域层纯净（仅 JDK）。由 L2 批量提取产出（模型输出经白名单校验后构造）；落库 UPSERT by news_id（拆批重试幂等）。 关键数字/标的只取原文与候选公司（幻觉防线，方案
 * §5 合规红线）；quote 为原文引用片段可回溯。
 */
public class EventItem {

    /** 原文引用截断上限（模板约束 ≤50 字，运行期 200 防御线——超长模型输出不撑爆列与卡片）。 */
    static final int QUOTE_MAX_LENGTH = 200;

    /** 摘要截断上限（防御线，模板约束一句话）。 */
    static final int SUMMARY_MAX_LENGTH = 500;

    private final Long id;
    private final long newsId;
    private final EventType eventType;
    private final String summary;
    private final List<String> affectedIndustries;
    private final Direction direction;
    private final Importance importance;
    private final List<KeyFigure> keyFigures;
    private final List<SubjectRef> subjects;
    private final String quote;
    private final Instant eventTime;
    private final String eventDate;
    private final String promptVersion;
    private final Instant createdAt;
    private final Instant updatedAt;

    private EventItem(
            Long id,
            long newsId,
            EventType eventType,
            String summary,
            List<String> affectedIndustries,
            Direction direction,
            Importance importance,
            List<KeyFigure> keyFigures,
            List<SubjectRef> subjects,
            String quote,
            Instant eventTime,
            String eventDate,
            String promptVersion,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.newsId = newsId;
        this.eventType = Objects.requireNonNull(eventType, "eventType 必填");
        this.summary = requireText(summary, "summary");
        this.affectedIndustries =
                affectedIndustries == null ? List.of() : List.copyOf(affectedIndustries);
        this.direction = Objects.requireNonNull(direction, "direction 必填");
        this.importance = Objects.requireNonNull(importance, "importance 必填");
        this.keyFigures = keyFigures == null ? List.of() : List.copyOf(keyFigures);
        this.subjects = subjects == null ? List.of() : List.copyOf(subjects);
        this.quote = quote;
        this.eventTime = eventTime;
        this.eventDate = requireText(eventDate, "eventDate");
        this.promptVersion = promptVersion;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建（L2 提取产物；id/时间戳由仓储回填）。 */
    public static EventItem create(
            long newsId,
            EventType eventType,
            String summary,
            List<String> affectedIndustries,
            Direction direction,
            Importance importance,
            List<KeyFigure> keyFigures,
            List<SubjectRef> subjects,
            String quote,
            Instant eventTime,
            String eventDate,
            String promptVersion) {
        return new EventItem(
                null,
                newsId,
                eventType,
                truncate(summary, SUMMARY_MAX_LENGTH),
                affectedIndustries,
                direction,
                importance,
                keyFigures,
                subjects,
                truncate(quote, QUOTE_MAX_LENGTH),
                eventTime,
                eventDate,
                promptVersion,
                null,
                null);
    }

    /** 从持久化数据重建（基础设施层回读）。 */
    public static EventItem reconstruct(
            Long id,
            long newsId,
            EventType eventType,
            String summary,
            List<String> affectedIndustries,
            Direction direction,
            Importance importance,
            List<KeyFigure> keyFigures,
            List<SubjectRef> subjects,
            String quote,
            Instant eventTime,
            String eventDate,
            String promptVersion,
            Instant createdAt,
            Instant updatedAt) {
        return new EventItem(
                id,
                newsId,
                eventType,
                summary,
                affectedIndustries,
                direction,
                importance,
                keyFigures,
                subjects,
                quote,
                eventTime,
                eventDate,
                promptVersion,
                createdAt,
                updatedAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填: " + value);
        }
        return value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public Long getId() {
        return id;
    }

    public long getNewsId() {
        return newsId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getAffectedIndustries() {
        return affectedIndustries;
    }

    public Direction getDirection() {
        return direction;
    }

    public Importance getImportance() {
        return importance;
    }

    public List<KeyFigure> getKeyFigures() {
        return keyFigures;
    }

    public List<SubjectRef> getSubjects() {
        return subjects;
    }

    public String getQuote() {
        return quote;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public String getEventDate() {
        return eventDate;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 关键数字（只取原文出现的数字，label+value+unit）。 */
    public record KeyFigure(String label, String value, String unit) {}

    /** 事件关联标的（回联标的池 code+name+industry；code 可空 = 未回联仅留名）。 */
    public record SubjectRef(String code, String name, String industry) {}
}
