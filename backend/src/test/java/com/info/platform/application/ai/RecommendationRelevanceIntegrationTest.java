package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * T29 推荐相关性链路集成自测（主路径/边界/异常三场景）：埋点落库 → 画像装配 → 相关性评分排序。
 *
 * <p>真实 beans + SQLite 共享内存库 + Flyway V14（依赖 V2 播种的标的 SH600519/SZ000001，id 按播种序 1/2）。 覆盖 PRD 场景 2
 * 的可操作链路：用户订阅「白酒」主题 + 阅读 SH600519 → 该标的在相关性排序中胜出且理由含命中因子。
 *
 * <p>@Transactional 每用例结束回滚，避免种子订阅/留痕行污染共享库的其他测试类（对齐 SubscriptionRepositoryImplTest 隔离惯例）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecommendationRelevanceIntegrationTest {

    /** V2 播种标的 id：SH600519（白酒）/ SZ000001（银行）。 */
    private static final long MOUTAI_ID = 1L;

    private static final long PINGAN_ID = 2L;

    @Autowired private ReadingEventService readingEventService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private RecommendationPersonalizer personalizer;
    @Autowired private RecommendationRelevanceScorer scorer;

    @BeforeEach
    void setUp() {
        UserContext.set(new UserContext.Principal(9001L, "t29-selftest"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void scenario1_main_readingEventsDrivePersonalizedRanking() {
        // Arrange（主路径）：订阅「白酒」主题 + 连续阅读 SH600519 详情 3 次（窗口去重下需隔窗口；
        // 集成库为独立实例且本用例唯一，首读落库即可画像命中）
        subscriptionRepository.save(Subscription.create(9001L, SubscriptionType.TOPIC, "白酒", null));
        boolean recorded =
                readingEventService.record(9001L, "SUBJECT_DETAIL", "SH600519", "SH600519", null);

        // Act：装配画像 → 评分（两只标的活跃度相同，仅 SH600519 有个性化命中）
        UserInterestProfile profile = personalizer.buildProfile(9001L);
        List<PoolMetric> metrics =
                List.of(
                        new PoolMetric("SH600519", "贵州茅台", 0.5, 0, 0, MOUTAI_ID, "白酒"),
                        new PoolMetric("SZ000001", "平安银行", 0.5, 0, 0, PINGAN_ID, "银行"));
        List<RecommendationRelevanceScorer.ScoredSubject> scored = scorer.score(metrics, profile);

        // Assert：落库成功；画像含主题词与已读统计；SH600519 综合分更高且理由点明命中因子
        assertThat(recorded).isTrue();
        assertThat(profile.themeKeywords()).containsExactly("白酒");
        assertThat(profile.readStatsBySubjectId()).containsKey(MOUTAI_ID);
        RecommendationRelevanceScorer.ScoredSubject moutai = scored.get(0);
        RecommendationRelevanceScorer.ScoredSubject pingan = scored.get(1);
        assertThat(moutai.relevanceScore()).isGreaterThan(pingan.relevanceScore());
        assertThat(moutai.reason())
                .contains("命中主题「白酒」+4.0")
                .contains("已读热度+3.3"); // 当天 1 次阅读：heat=1.0 → 10.0×(1/3)=3.3
    }

    @Test
    void scenario2_boundary_newUserFallsBackToActivityOrdering() {
        // Arrange（边界）：全新用户 9002（无订阅无阅读）
        long newUser = 9002L;
        List<PoolMetric> metrics =
                List.of(
                        new PoolMetric("SH600519", "贵州茅台", 1.0, 0, 0, MOUTAI_ID, "白酒"),
                        new PoolMetric("SZ000001", "平安银行", 3.0, 1, 2, PINGAN_ID, "银行"));

        // Act
        UserInterestProfile profile = personalizer.buildProfile(newUser);
        List<RecommendationRelevanceScorer.ScoredSubject> scored = scorer.score(metrics, profile);

        // Assert：空画像 → 纯活跃度（平安 7.0 > 茅台 1.0），理由为活跃度口径（既有行为不变）
        assertThat(profile.isPersonalized()).isFalse();
        RecommendationRelevanceScorer.ScoredSubject moutai = scored.get(0);
        RecommendationRelevanceScorer.ScoredSubject pingan = scored.get(1);
        assertThat(moutai.metric().subjectCode()).isEqualTo("SH600519");
        assertThat(pingan.metric().subjectCode()).isEqualTo("SZ000001");
        assertThat(pingan.relevanceScore()).isGreaterThan(moutai.relevanceScore());
        assertThat(pingan.reason()).contains("综合分7.0").doesNotContain("个性化相关");
    }

    @Test
    void scenario3_exception_invalidTrackingPayloadRejected() {
        // Arrange（异常）：非法内容类型 / 空引用 → 2001（400）；去重窗口内重复上报 recorded=false 不报错

        // Act / Assert：未知类型拒绝
        assertThatThrownBy(() -> readingEventService.record(9001L, "VIDEO", "x", null, null))
                .isInstanceOf(BusinessException.class);
        // 空引用拒绝
        assertThatThrownBy(() -> readingEventService.record(9001L, "POLICY", " ", null, null))
                .isInstanceOf(BusinessException.class);
        // 窗口内重复：同 user+type+ref 连续两次，第二次去重（幂等友好，非错误）
        boolean first = readingEventService.record(9001L, "POLICY", "selftest-policy", null, null);
        boolean second = readingEventService.record(9001L, "POLICY", "selftest-policy", null, null);
        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }
}
