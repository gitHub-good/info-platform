package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.ai.ReadingEvent;
import com.info.platform.domain.ai.ReadingEventRepository;
import com.info.platform.domain.ai.ReadingEventType;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionStatus;
import com.info.platform.domain.subscription.SubscriptionType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RecommendationPersonalizer 单测（T29）：画像装配主路径（订阅拆主题/标的 + 阅读聚合衰减）/ 新用户空画像 / 降级容错。AAA 结构。
 *
 * <p>mock 三个仓储端口，Clock 固定（2026-09-22），衰减断言确定性。
 */
class RecommendationPersonalizerTest {

    private static final long USER_ID = 1L;

    /** 固定现在：2026-09-22T00:00:00Z。 */
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SubscriptionRepository subscriptionRepository;
    private ReadingEventRepository readingEventRepository;
    private SubjectRepository subjectRepository;
    private RecommendationPersonalizer personalizer;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        readingEventRepository = mock(ReadingEventRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        personalizer =
                new RecommendationPersonalizer(
                        subscriptionRepository, readingEventRepository, subjectRepository, CLOCK);
        when(subscriptionRepository.findByOwnerIdCursor(anyLong(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(readingEventRepository.findByUserSince(anyLong(), any(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void buildProfile_normal_assemblesThemesSubjectsAndDecayedReads() {
        // Arrange：主题+政策主题+标的订阅各一；标的 100 今天读 2 次、8 天前读 1 次
        when(subscriptionRepository.findByOwnerIdCursor(eq(USER_ID), any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                sub(SubscriptionType.TOPIC, "半导体"),
                                sub(SubscriptionType.POLICY_THEME, "货币政策"),
                                sub(SubscriptionType.SUBJECT, "100")));
        when(readingEventRepository.findByUserSince(eq(USER_ID), any(), anyInt()))
                .thenReturn(
                        List.of(
                                read(100L, NOW), // 当天权重 1.0
                                read(100L, NOW.minusSeconds(3600)), // 当天权重 1.0
                                read(
                                        100L,
                                        NOW.minus(
                                                java.time.Duration.ofDays(
                                                        8))))); // 8 天前权重 0.5^(8/7)
        when(subjectRepository.findById(100L))
                .thenReturn(Optional.of(subject(100L, "SH600519", "贵州茅台", "白酒")));

        // Act
        UserInterestProfile profile = personalizer.buildProfile(USER_ID);

        // Assert：主题词两个（保序去重）、标的订阅解析、阅读按半衰期聚合（2 + 0.5^(8/7)≈2.457）
        assertThat(profile.isPersonalized()).isTrue();
        assertThat(profile.themeKeywords()).containsExactly("半导体", "货币政策");
        assertThat(profile.subscribedSubjects()).hasSize(1);
        assertThat(profile.subscribedSubjects().get(0).subjectId()).isEqualTo(100L);
        assertThat(profile.readStats()).hasSize(1);
        UserInterestProfile.SubjectReadStat stat = profile.readStats().get(0);
        assertThat(stat.count()).isEqualTo(3);
        assertThat(stat.code()).isEqualTo("SH600519");
        assertThat(stat.heat()).isCloseTo(2.0 + Math.pow(0.5, 8.0 / 7), within(1e-9));
        assertThat(stat.lastReadDate()).isEqualTo(LocalDate.ofInstant(NOW, ZoneId.systemDefault()));
    }

    @Test
    void buildProfile_noSubsNoReads_emptyProfile() {
        // Arrange：新用户（边界：无订阅无阅读）

        // Act
        UserInterestProfile profile = personalizer.buildProfile(USER_ID);

        // Assert：空画像合法（评分侧退化活跃度排序）
        assertThat(profile.isPersonalized()).isFalse();
        assertThat(profile.themeKeywords()).isEmpty();
        assertThat(profile.readStats()).isEmpty();
    }

    @Test
    void buildProfile_unsubscribedAndInvalidSkipped() {
        // Arrange：已退订主题不进画像；标的订阅 subKey 非数字跳过；无标的关联阅读跳过
        when(subscriptionRepository.findByOwnerIdCursor(eq(USER_ID), any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                unsubscribed(SubscriptionType.TOPIC, "已退订主题"),
                                sub(SubscriptionType.SUBJECT, "not-a-number")));
        when(readingEventRepository.findByUserSince(eq(USER_ID), any(), anyInt()))
                .thenReturn(List.of(read(null, NOW)));

        // Act
        UserInterestProfile profile = personalizer.buildProfile(USER_ID);

        // Assert：无主题词、无标的订阅、无已读统计
        assertThat(profile.isPersonalized()).isFalse();
    }

    @Test
    void buildProfile_readStatsOrderedByHeatDesc() {
        // Arrange：标的 200 热（今天 3 次）、标的 100 冷（29 天前 1 次）
        when(readingEventRepository.findByUserSince(eq(USER_ID), any(), anyInt()))
                .thenReturn(
                        List.of(
                                read(100L, NOW.minus(java.time.Duration.ofDays(29))),
                                read(200L, NOW),
                                read(200L, NOW),
                                read(200L, NOW)));
        when(subjectRepository.findById(anyLong())).thenReturn(Optional.empty());

        // Act
        UserInterestProfile profile = personalizer.buildProfile(USER_ID);

        // Assert：热度降序（200 在前）
        assertThat(profile.readStats()).hasSize(2);
        assertThat(profile.readStats().get(0).subjectId()).isEqualTo(200L);
        assertThat(profile.readStats().get(0).code()).isNull(); // 已删标的 code 为 null 占位
    }

    @Test
    void buildProfile_repoFailure_degradesToEmptyProfile() {
        // Arrange：订阅仓储抛异常（异常路径：个性化失败不拖垮推荐主链路）
        when(subscriptionRepository.findByOwnerIdCursor(anyLong(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        // Act
        UserInterestProfile profile = personalizer.buildProfile(USER_ID);

        // Assert：空画像降级，不抛
        assertThat(profile.isPersonalized()).isFalse();
    }

    // ==================== fixtures ====================

    private static Subscription sub(SubscriptionType type, String subKey) {
        return Subscription.reconstruct(
                1L,
                USER_ID,
                type,
                subKey,
                SubscriptionChannel.IN_APP,
                SubscriptionStatus.SUBSCRIBED,
                0L,
                NOW,
                NOW);
    }

    private static Subscription unsubscribed(SubscriptionType type, String subKey) {
        return Subscription.reconstruct(
                2L,
                USER_ID,
                type,
                subKey,
                SubscriptionChannel.IN_APP,
                SubscriptionStatus.UNSUBSCRIBED,
                0L,
                NOW,
                NOW);
    }

    private static ReadingEvent read(Long subjectId, Instant at) {
        return ReadingEvent.reconstruct(
                null, USER_ID, subjectId, ReadingEventType.SUBJECT_DETAIL, "ref", at);
    }

    private static Subject subject(long id, String code, String name, String industry) {
        return Subject.reconstruct(
                id,
                SubjectCode.of(code),
                Market.A_SHARE,
                SubjectType.STOCK,
                name,
                Map.of(),
                industry,
                SubjectStatus.ENABLED,
                0L,
                NOW,
                NOW);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
