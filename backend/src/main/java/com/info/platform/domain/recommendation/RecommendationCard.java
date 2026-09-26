package com.info.platform.domain.recommendation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 推荐卡片实体（{@code recommendation_card}，M16 方案 §4.1/§4.5）：一用户一事件一卡（{@code UNIQUE(user_id, event_id)}
 * 幂等最后防线）。
 *
 * <p>领域层纯净（仅 JDK；industries/subjects 为结构化集合，JSON 序列化由基础设施层承担）。生成走 {@link #create} （T132 卡片服务组装
 * logicChain/logicInputs 后建卡）；推送状态迁移（PENDING → PUSHED/SKIPPED_*）由仓储条件 UPDATE 承载（T133 推送闸门），实体只读投影 +
 * 状态白名单。
 */
public class RecommendationCard {

    /** 标的区条目（卡片标的区 ≤5；inWatchlist 供「加自选」按钮判断）。 */
    public record CardSubject(String code, String name, String industry, boolean inWatchlist) {}

    private final Long id;
    private final long userId;
    private final long eventId;
    private final long newsId;
    private final String eventType;
    private final String importance;
    private final String direction;
    private final RecLevel level;
    private final List<String> industries;
    private final List<CardSubject> subjects;
    private final String logicChain;
    private final String logicInputs;
    private final CardGenMethod genMethod;
    private final String promptVersion;
    private final double recscore;
    private final String basis;
    private final String comboKey;
    private final CardPushStatus pushStatus;
    private final Instant pushedAt;
    private final boolean read;
    private final boolean adopted;
    private final Instant createdAt;
    private final Instant updatedAt;

    private RecommendationCard(
            Long id,
            long userId,
            long eventId,
            long newsId,
            String eventType,
            String importance,
            String direction,
            RecLevel level,
            List<String> industries,
            List<CardSubject> subjects,
            String logicChain,
            String logicInputs,
            CardGenMethod genMethod,
            String promptVersion,
            double recscore,
            String basis,
            String comboKey,
            CardPushStatus pushStatus,
            Instant pushedAt,
            boolean read,
            boolean adopted,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.eventId = eventId;
        this.newsId = newsId;
        this.eventType = requireText(eventType, "eventType");
        this.importance = requireText(importance, "importance");
        this.direction = requireText(direction, "direction");
        this.level = Objects.requireNonNull(level, "level 必填");
        this.industries = industries == null ? List.of() : List.copyOf(industries);
        this.subjects = subjects == null ? List.of() : List.copyOf(subjects);
        this.logicChain = requireText(logicChain, "logicChain");
        this.logicInputs = logicInputs;
        this.genMethod = Objects.requireNonNull(genMethod, "genMethod 必填");
        this.promptVersion = promptVersion;
        this.recscore = recscore;
        this.basis = requireText(basis, "basis");
        this.comboKey = requireText(comboKey, "comboKey");
        this.pushStatus = Objects.requireNonNull(pushStatus, "pushStatus 必填");
        this.pushedAt = pushedAt;
        this.read = read;
        this.adopted = adopted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 新建卡片（生成产物；id/时间戳由仓储回填；pushStatus 起点恒 PENDING——推送闸门另行迁移）。
     *
     * @param logicInputs 生成输入快照 JSON（结构化事实 + 四类允许集，抽检对账与复现用）
     */
    public static RecommendationCard create(
            long userId,
            long eventId,
            long newsId,
            String eventType,
            String importance,
            String direction,
            RecLevel level,
            List<String> industries,
            List<CardSubject> subjects,
            String logicChain,
            String logicInputs,
            CardGenMethod genMethod,
            String promptVersion,
            double recscore,
            String basis,
            String comboKey) {
        return new RecommendationCard(
                null,
                userId,
                eventId,
                newsId,
                eventType,
                importance,
                direction,
                level,
                industries,
                subjects,
                logicChain,
                logicInputs,
                genMethod,
                promptVersion,
                recscore,
                basis,
                comboKey,
                CardPushStatus.PENDING,
                null,
                false,
                false,
                null,
                null);
    }

    /** 从持久化数据重建（基础设施层回读）。 */
    public static RecommendationCard reconstruct(
            Long id,
            long userId,
            long eventId,
            long newsId,
            String eventType,
            String importance,
            String direction,
            RecLevel level,
            List<String> industries,
            List<CardSubject> subjects,
            String logicChain,
            String logicInputs,
            CardGenMethod genMethod,
            String promptVersion,
            double recscore,
            String basis,
            String comboKey,
            CardPushStatus pushStatus,
            Instant pushedAt,
            boolean read,
            boolean adopted,
            Instant createdAt,
            Instant updatedAt) {
        return new RecommendationCard(
                id,
                userId,
                eventId,
                newsId,
                eventType,
                importance,
                direction,
                level,
                industries,
                subjects,
                logicChain,
                logicInputs,
                genMethod,
                promptVersion,
                recscore,
                basis,
                comboKey,
                pushStatus,
                pushedAt,
                read,
                adopted,
                createdAt,
                updatedAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填: " + value);
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public long getEventId() {
        return eventId;
    }

    public long getNewsId() {
        return newsId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getImportance() {
        return importance;
    }

    public String getDirection() {
        return direction;
    }

    public RecLevel getLevel() {
        return level;
    }

    public List<String> getIndustries() {
        return industries;
    }

    public List<CardSubject> getSubjects() {
        return subjects;
    }

    public String getLogicChain() {
        return logicChain;
    }

    public String getLogicInputs() {
        return logicInputs;
    }

    public CardGenMethod getGenMethod() {
        return genMethod;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public double getRecscore() {
        return recscore;
    }

    public String getBasis() {
        return basis;
    }

    public String getComboKey() {
        return comboKey;
    }

    public CardPushStatus getPushStatus() {
        return pushStatus;
    }

    public Instant getPushedAt() {
        return pushedAt;
    }

    public boolean isRead() {
        return read;
    }

    public boolean isAdopted() {
        return adopted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
