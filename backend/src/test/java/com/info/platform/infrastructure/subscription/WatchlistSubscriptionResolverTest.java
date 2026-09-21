package com.info.platform.infrastructure.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.push.SubscriptionResolver;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * WatchlistSubscriptionResolver 集成测试（T14）：SQLite + Flyway V4（watchlist/watchlist_item）建表后， 经 {@link
 * WatchlistRepository} 种子数据，验证 {@link SubscriptionResolver#resolveAnomalyTargets} 的 watchlist
 * 隐含订阅解析（JOIN 过滤 已删除清单 + DISTINCT 去重）。 @Transactional 每用例回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WatchlistSubscriptionResolverTest {

    @Autowired private SubscriptionResolver subscriptionResolver;
    @Autowired private WatchlistRepository watchlistRepository;

    private static final long SUBJECT_ID = 600519L;
    private static final long OTHER_SUBJECT_ID = 600036L;

    private Watchlist createWatchlist(long userId, String name) {
        return watchlistRepository.save(Watchlist.create(userId, name, null));
    }

    private void addItem(Long watchlistId, Long subjectId) {
        watchlistRepository.saveItem(
                WatchlistItem.create(watchlistId, subjectId, new BigDecimal("3.00")));
    }

    @Test
    void resolveAnomalyTargets_multiUsersWatchingSubject_returnsDistinctUserIds() {
        // Arrange：user1、user2 各自清单含该标的；user3 清单含另一标的（不应命中）
        addItem(createWatchlist(1L, "u1-default").getId(), SUBJECT_ID);
        addItem(createWatchlist(2L, "u2-default").getId(), SUBJECT_ID);
        addItem(createWatchlist(3L, "u3-default").getId(), OTHER_SUBJECT_ID);

        // Act
        Set<Long> targets = subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID);

        // Assert：仅含该标的的两个用户
        assertThat(targets).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void resolveAnomalyTargets_noWatcher_returnsEmpty() {
        // Arrange：无人订阅该标的
        addItem(createWatchlist(1L, "other").getId(), OTHER_SUBJECT_ID);

        // Act + Assert
        assertThat(subscriptionResolver.resolveAnomalyTargets(9999L)).isEmpty();
    }

    @Test
    void resolveAnomalyTargets_sameUserTwoWatchlists_dedupsToSingleTarget() {
        // Arrange：同一用户两个清单都含该标的 → DISTINCT 去重为 1 个目标
        Long wl1 = createWatchlist(7L, "wl-a").getId();
        Long wl2 = createWatchlist(7L, "wl-b").getId();
        addItem(wl1, SUBJECT_ID);
        addItem(wl2, SUBJECT_ID);

        // Act + Assert：去重，不重复推
        assertThat(subscriptionResolver.resolveAnomalyTargets(SUBJECT_ID)).containsExactly(7L);
    }

    @Test
    void resolveAnomalyTargets_nullSubjectId_returnsEmpty() {
        assertThat(subscriptionResolver.resolveAnomalyTargets(null)).isEmpty();
    }
}
