package com.info.platform.infrastructure.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.push.SubscriptionResolver;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionType;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * SubscriptionConfigSubscriptionResolver 集成测试（T26）：SQLite + Flyway V11（subscription_config）建表后， 经
 * {@link SubscriptionRepository} 种子数据，验证 {@link SubscriptionResolver#resolveAnomalyTargets} 的精细订阅解析
 * （按 sub_type=2(标的) + sub_key=subjectId + status=1(订阅中) 查用户集合，替代 M1 watchlist 隐含订阅）。
 *
 * <p>本测试 {@code @Autowired SubscriptionResolver} 注入的是 {@link
 * SubscriptionConfigSubscriptionResolver}（标 {@code @Primary}， 覆盖 M1 {@link
 * WatchlistSubscriptionResolver}）——即 T26 切换后 PushService 实际使用的实现。 @Transactional 每用例回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubscriptionConfigSubscriptionResolverTest {

    @Autowired private SubscriptionResolver subscriptionResolver;
    @Autowired private SubscriptionRepository subscriptionRepository;

    private static final long SUBJECT_ID = 600519L;
    private static final long OTHER_SUBJECT_ID = 600036L;

    private void subscribe(long userId, SubscriptionType type, String subKey) {
        subscriptionRepository.save(
                Subscription.create(userId, type, subKey, SubscriptionChannel.IN_APP));
    }

    @Test
    void resolveAnomalyTargets_multiUsersSubscribedSubject_returnsDistinctUserIds() {
        // Arrange：user1、user2 显式订阅标的 600519；user3 订阅另一标的（不应命中）
        subscribe(1L, SubscriptionType.SUBJECT, String.valueOf(SUBJECT_ID));
        subscribe(2L, SubscriptionType.SUBJECT, String.valueOf(SUBJECT_ID));
        subscribe(3L, SubscriptionType.SUBJECT, String.valueOf(OTHER_SUBJECT_ID));

        // Act
        Set<Long> targets = subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID);

        // Assert：仅含订阅该标的的两个用户（精细订阅，非 watchlist 隐含）
        assertThat(targets).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void resolveAnomalyTargets_noSubscribers_returnsEmpty() {
        // Arrange：无人订阅该标的
        subscribe(1L, SubscriptionType.SUBJECT, String.valueOf(OTHER_SUBJECT_ID));

        // Act + Assert
        assertThat(subscriptionResolver.resolveAnomalyTargets(9999L)).isEmpty();
    }

    @Test
    void resolveAnomalyTargets_unsubscribedExcluded_onlyActiveReturned() {
        // Arrange：userA 订阅中、userB 退订（status=0）→ 退订后不推送（PRD 故事 5 场景 3）
        subscribe(1L, SubscriptionType.SUBJECT, String.valueOf(SUBJECT_ID));
        Subscription unsubscribed =
                subscriptionRepository.save(
                        Subscription.create(
                                2L,
                                SubscriptionType.SUBJECT,
                                String.valueOf(SUBJECT_ID),
                                SubscriptionChannel.IN_APP));
        unsubscribed.unsubscribe();
        subscriptionRepository.save(unsubscribed); // 翻 status=0（软退订）

        // Act
        Set<Long> targets = subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID);

        // Assert：仅返回订阅中的 userA，已退订的 userB 不命中
        assertThat(targets).containsExactly(1L);
    }

    @Test
    void resolveAnomalyTargets_nonSubjectTypeDoesNotMatch() {
        // Arrange：user1 订阅「主题」类型 sub_key=600519（sub_type=1）→ resolveAnomalyTargets 查
        // sub_type=2(标的)，不应命中
        subscribe(1L, SubscriptionType.TOPIC, String.valueOf(SUBJECT_ID));

        // Act + Assert：主题订阅不参与异动推送目标解析（仅标的订阅命中）
        assertThat(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).isEmpty();
    }

    @Test
    void resolveAnomalyTargets_nullSubjectId_returnsEmpty() {
        assertThat(subscriptionResolver.resolveAnomalyTargets(null)).isEmpty();
    }
}
