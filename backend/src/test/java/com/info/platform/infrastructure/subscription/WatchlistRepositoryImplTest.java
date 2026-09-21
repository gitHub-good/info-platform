package com.info.platform.infrastructure.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * WatchlistRepositoryImpl 集成测试（T11）：SQLite 共享内存库 + Flyway V4 建表后， 测 save/findAll/行级过滤/清单项
 * CRUD/UNIQUE 幂等约束/乐观锁。 @SpringBootTest 启动完整上下文（含 Flyway 迁移）， @Transactional 每个用例结束回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WatchlistRepositoryImplTest {

    @Autowired private WatchlistRepository repository;

    private static final long USER_A = 9001L;
    private static final long USER_B = 9002L;

    @Test
    void save_newWatchlist_thenFindByOwnerIdAndId_roundTrip() {
        // Arrange
        Watchlist created = repository.save(Watchlist.create(USER_A, "我的清单", "备注"));

        // Act
        Optional<Watchlist> loaded = repository.findByOwnerIdAndId(USER_A, created.getId());

        // Assert
        assertThat(created.getId()).isNotNull();
        assertThat(created.getUserId()).isEqualTo(USER_A);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getName()).isEqualTo("我的清单");
        assertThat(loaded.get().getRemark()).isEqualTo("备注");
        assertThat(loaded.get().getStatus()).isEqualTo(WatchlistStatus.ENABLED);
        assertThat(loaded.get().getItems()).isEmpty(); // 新清单无清单项
    }

    @Test
    void findAllByOwnerId_returnsOnlyOwnWithItems() {
        // Arrange：A 两个清单各加一项，B 一个清单
        Watchlist a1 = repository.save(Watchlist.create(USER_A, "A清单1", null));
        Watchlist a2 = repository.save(Watchlist.create(USER_A, "A清单2", null));
        repository.save(Watchlist.create(USER_B, "B清单", null));
        repository.saveItem(WatchlistItem.create(a1.getId(), 600519L, new BigDecimal("3.00")));
        repository.saveItem(WatchlistItem.create(a1.getId(), 600036L, null)); // 默认阈值
        repository.saveItem(WatchlistItem.create(a2.getId(), 1L, new BigDecimal("5.00")));

        // Act
        List<Watchlist> lists = repository.findAllByOwnerId(USER_A);

        // Assert：只返回 A 的两个清单，各含其清单项（行级过滤 + 批量装载）
        assertThat(lists).hasSize(2);
        Watchlist first = lists.get(0);
        assertThat(first.getId()).isEqualTo(a1.getId());
        assertThat(first.getItems()).hasSize(2);
        assertThat(first.getItems())
                .extracting(WatchlistItem::getSubjectId)
                .contains(600519L, 600036L);
        // 默认阈值落库
        assertThat(
                        first.getItems().stream()
                                .filter(i -> 600036L == i.getSubjectId())
                                .findFirst()
                                .orElseThrow()
                                .getAnomalyThreshold())
                .isEqualByComparingTo("3.00");
        Watchlist second = lists.get(1);
        assertThat(second.getItems()).hasSize(1);
    }

    @Test
    void existsByOwnerIdAndName_scopedToOwner() {
        repository.save(Watchlist.create(USER_A, "同名清单", null));
        repository.save(Watchlist.create(USER_B, "同名清单", null)); // 不同用户同名不冲突

        assertThat(repository.existsByOwnerIdAndName(USER_A, "同名清单")).isTrue();
        assertThat(repository.existsByOwnerIdAndName(USER_B, "同名清单")).isTrue();
        assertThat(repository.existsByOwnerIdAndName(USER_A, "不存在")).isFalse();
    }

    @Test
    void existsById_ignoresOwner() {
        Watchlist saved = repository.save(Watchlist.create(USER_A, "存在性", null));

        assertThat(repository.existsById(saved.getId())).isTrue();
        assertThat(repository.existsById(999999L)).isFalse();
    }

    @Test
    void rowLevel_findByOwnerIdAndId_otherUserReturnsEmpty() {
        Watchlist aList = repository.save(Watchlist.create(USER_A, "A的清单", null));

        // 行级过滤：B 查 A 的清单 → 空（非本人读不到）
        assertThat(repository.findByOwnerIdAndId(USER_B, aList.getId())).isEmpty();
        assertThat(repository.existsByOwnerIdAndId(USER_B, aList.getId())).isFalse();
        assertThat(repository.existsByOwnerIdAndId(USER_A, aList.getId())).isTrue();
    }

    @Test
    void addItem_thenExistsItemByWatchlistAndSubject_true() {
        Watchlist wl = repository.save(Watchlist.create(USER_A, "加标的", null));
        repository.saveItem(WatchlistItem.create(wl.getId(), 600519L, new BigDecimal("4.50")));

        assertThat(repository.existsItemByWatchlistAndSubject(wl.getId(), 600519L)).isTrue();
        assertThat(repository.existsItemByWatchlistAndSubject(wl.getId(), 600036L)).isFalse();
    }

    @Test
    void addItem_duplicateSubject_uniqueConstraintFires() {
        // DB UNIQUE(watchlist_id, subject_id) 为加标的幂等最后防线：重复插入触发 SQLite 唯一约束。
        // 注：MyBatis-Plus+SQLite 下 UNIQUE 违例落为 UncategorizedSQLException（未翻译为
        // DuplicateKeyException），故应用层靠服务级 existsItemByWatchlistAndSubject 预检返回 30011/409，
        // DB 异常只在预检漏掉的极小概率竞态下兜底（→500）。此处断言约束确实存在并触发。
        Watchlist wl = repository.save(Watchlist.create(USER_A, "幂等", null));
        repository.saveItem(WatchlistItem.create(wl.getId(), 600519L, null));

        assertThatThrownBy(
                        () -> repository.saveItem(WatchlistItem.create(wl.getId(), 600519L, null)))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("UNIQUE constraint failed: watchlist_item");
    }

    @Test
    void deleteItem_thenReAddSameSubjectSucceeds() {
        // 物理删除释放 UNIQUE 约束，便于重新加标的
        Watchlist wl = repository.save(Watchlist.create(USER_A, "删后重加", null));
        WatchlistItem item = repository.saveItem(WatchlistItem.create(wl.getId(), 600519L, null));

        boolean deleted = repository.deleteItemByWatchlistAndItemId(wl.getId(), item.getId());
        assertThat(deleted).isTrue();
        assertThat(repository.existsItemByWatchlistAndSubject(wl.getId(), 600519L)).isFalse();

        // 重新加同标的不再冲突
        WatchlistItem readded =
                repository.saveItem(
                        WatchlistItem.create(wl.getId(), 600519L, new BigDecimal("2.00")));
        assertThat(readded.getId()).isNotNull();
        assertThat(readded.getAnomalyThreshold()).isEqualByComparingTo("2.00");
    }

    @Test
    void findItemByWatchlistAndItemId_filtersByWatchlist() {
        Watchlist wl = repository.save(Watchlist.create(USER_A, "定位项", null));
        WatchlistItem item = repository.saveItem(WatchlistItem.create(wl.getId(), 600519L, null));

        assertThat(repository.findItemByWatchlistAndItemId(wl.getId(), item.getId())).isPresent();
        assertThat(repository.findItemByWatchlistAndItemId(wl.getId(), 999999L)).isEmpty();
    }

    @Test
    void saveItem_updateThreshold_incrementsVersion() {
        // 乐观锁：load→改→save→reload，version+1、阈值更新
        Watchlist wl = repository.save(Watchlist.create(USER_A, "改阈值", null));
        WatchlistItem item =
                repository.saveItem(
                        WatchlistItem.create(wl.getId(), 600519L, new BigDecimal("3.00")));
        assertThat(item.getVersion()).isZero();

        item.updateThreshold(new BigDecimal("6.00"));
        WatchlistItem updated = repository.saveItem(item);

        Optional<WatchlistItem> reloaded =
                repository.findItemByWatchlistAndItemId(wl.getId(), updated.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getAnomalyThreshold()).isEqualByComparingTo("6.00");
        assertThat(reloaded.get().getVersion()).isEqualTo(1L);
    }

    @Test
    void deleteItemByWatchlistAndItemId_wrongWatchlistReturnsFalse() {
        // 跨清单删除被 watchlist_id 过滤拦截
        Watchlist wlA = repository.save(Watchlist.create(USER_A, "A", null));
        Watchlist wlB = repository.save(Watchlist.create(USER_A, "B", null));
        WatchlistItem itemB = repository.saveItem(WatchlistItem.create(wlB.getId(), 600519L, null));

        // 用 wlA 删 wlB 的项 → 不命中（行级）
        boolean deleted = repository.deleteItemByWatchlistAndItemId(wlA.getId(), itemB.getId());
        assertThat(deleted).isFalse();
        assertThat(repository.findItemByWatchlistAndItemId(wlB.getId(), itemB.getId())).isPresent();
    }
}
