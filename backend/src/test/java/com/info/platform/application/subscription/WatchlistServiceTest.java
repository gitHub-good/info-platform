package com.info.platform.application.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * WatchlistService 单测（T11）：CRUD 编排 + 行级权限 + 幂等，AAA 结构。
 *
 * <p>mock {@link WatchlistRepository} 与 {@link SubjectRepository}； {@link UserContext}
 * 在 @BeforeEach 写入当前用户 （模拟 JwtAuthFilter），@AfterEach 清空防线程池复用串味。 行级权限：用户 A（userId=1）不能操作用户 B
 * 的清单→30012。
 */
class WatchlistServiceTest {

    private static final long ME = 1L;
    private static final long OTHER_USER = 2L;
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    private WatchlistRepository repository;
    private SubjectRepository subjectRepository;
    private WatchlistService service;

    @BeforeEach
    void setUp() {
        repository = mock(WatchlistRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        service = new WatchlistService(repository, subjectRepository);
        UserContext.set(new UserContext.Principal(ME, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---- list / get ----

    @Test
    void listMyWatchlists_returnsOnlyOwnWatchlistsWithItems() {
        Watchlist mine = watchlist(1L, ME, "我的清单", item(10L, 1L, 100L, "3.00"));
        Watchlist others = watchlist(2L, OTHER_USER, "他人清单", item(11L, 2L, 200L, "3.00"));
        // 仓储按 ownerUserId 过滤，只返回本人清单（行级约束在端口层）
        when(repository.findAllByOwnerId(ME)).thenReturn(List.of(mine));

        List<WatchlistView> result = service.listMyWatchlists();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).items()).hasSize(1);
        assertThat(result.get(0).items().get(0).subjectId()).isEqualTo(100L);
        verify(repository).findAllByOwnerId(ME);
        // others 仅用于断言仓储不会误取他人清单
        assertThat(others.getUserId()).isEqualTo(OTHER_USER);
    }

    @Test
    void getWatchlist_owned_returnsViewWithItems() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 1L))
                .thenReturn(Optional.of(watchlist(1L, ME, "我的清单", item(10L, 1L, 100L, "3.00"))));

        WatchlistView result = service.getWatchlist(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void getWatchlist_notExists_throws30010() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.getWatchlist(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND);
        verify(repository, never()).findByOwnerIdAndId(anyLong(), anyLong());
    }

    @Test
    void getWatchlist_notOwned_throws30012() {
        // 行级权限：清单存在但非本人 → 30012（不泄露是否存在）
        when(repository.existsById(2L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWatchlist(2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_FORBIDDEN);
    }

    // ---- create ----

    @Test
    void createWatchlist_newName_savesAndReturnsView() {
        when(repository.existsByOwnerIdAndName(ME, "我的清单")).thenReturn(false);
        when(repository.save(any(Watchlist.class))).thenReturn(watchlist(1L, ME, "我的清单"));

        WatchlistView result = service.createWatchlist("我的清单", "备注");

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("我的清单");
        ArgumentCaptor<Watchlist> captor = ArgumentCaptor.forClass(Watchlist.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(ME); // 归属当前用户
    }

    @Test
    void createWatchlist_sameName_throws30011_noDuplicateRow() {
        // 幂等（自然键 userId+name）：同名→30011，不产生重复行
        when(repository.existsByOwnerIdAndName(ME, "我的清单")).thenReturn(true);

        assertThatThrownBy(() -> service.createWatchlist("我的清单", "备注"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST);
        verify(repository, never()).save(any(Watchlist.class));
    }

    // ---- add item ----

    @Test
    void addItem_owned_subjectExists_notInList_savesItem() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(subjectRepository.findById(100L)).thenReturn(Optional.of(subject(100L)));
        when(repository.existsItemByWatchlistAndSubject(1L, 100L)).thenReturn(false);
        when(repository.saveItem(any(WatchlistItem.class))).thenReturn(item(10L, 1L, 100L, "3.00"));

        WatchlistItemView result = service.addItem(1L, 100L, new BigDecimal("5.00"));

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.subjectId()).isEqualTo(100L);
    }

    @Test
    void addItem_nullThreshold_defaultsToThree() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(subjectRepository.findById(100L)).thenReturn(Optional.of(subject(100L)));
        when(repository.existsItemByWatchlistAndSubject(1L, 100L)).thenReturn(false);
        when(repository.saveItem(any(WatchlistItem.class))).thenReturn(item(10L, 1L, 100L, "3.00"));

        service.addItem(1L, 100L, null);

        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        verify(repository).saveItem(captor.capture());
        assertThat(captor.getValue().getAnomalyThreshold()).isEqualByComparingTo("3.00");
    }

    @Test
    void addItem_watchlistNotExists_throws30010() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.addItem(999L, 100L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND);
        verify(subjectRepository, never()).findById(anyLong());
    }

    @Test
    void addItem_notOwned_throws30012() {
        // 行级权限：用户 A 不能往用户 B 的清单加标的 → 30012
        when(repository.existsById(2L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 2L)).thenReturn(false);

        assertThatThrownBy(() -> service.addItem(2L, 100L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_FORBIDDEN);
        verify(subjectRepository, never()).findById(anyLong());
    }

    @Test
    void addItem_subjectNotFound_throws30001() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(subjectRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(1L, 999L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SUBJECT_NOT_FOUND);
        verify(repository, never()).saveItem(any());
    }

    @Test
    void addItem_alreadyInList_throws30011_noDuplicateRow() {
        // 幂等（自然键 userId+watchlistId+subjectId）：已在清单→30011
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(subjectRepository.findById(100L)).thenReturn(Optional.of(subject(100L)));
        when(repository.existsItemByWatchlistAndSubject(1L, 100L)).thenReturn(true);

        assertThatThrownBy(() -> service.addItem(1L, 100L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST);
        verify(repository, never()).saveItem(any());
    }

    // ---- remove ----

    @Test
    void removeItem_owned_deletesItem() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(repository.deleteItemByWatchlistAndItemId(1L, 10L)).thenReturn(true);

        service.removeItem(1L, 10L);

        verify(repository).deleteItemByWatchlistAndItemId(1L, 10L);
    }

    @Test
    void removeItem_itemNotFound_isIdempotentNoThrow() {
        // 幂等移除：清单项已不存在不报错
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(repository.deleteItemByWatchlistAndItemId(1L, 10L)).thenReturn(false);

        service.removeItem(1L, 10L); // 不抛异常
        verify(repository).deleteItemByWatchlistAndItemId(1L, 10L);
    }

    @Test
    void removeItem_notOwned_throws30012() {
        when(repository.existsById(2L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 2L)).thenReturn(false);

        assertThatThrownBy(() -> service.removeItem(2L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_FORBIDDEN);
        verify(repository, never()).deleteItemByWatchlistAndItemId(anyLong(), anyLong());
    }

    // ---- update threshold ----

    @Test
    void updateThreshold_owned_itemExists_updatesAndReturnsView() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(repository.findItemByWatchlistAndItemId(1L, 10L))
                .thenReturn(Optional.of(item(10L, 1L, 100L, "3.00")));
        when(repository.saveItem(any(WatchlistItem.class))).thenReturn(item(10L, 1L, 100L, "5.00"));

        WatchlistItemView result = service.updateItemThreshold(1L, 10L, new BigDecimal("5.00"));

        assertThat(result.anomalyThreshold()).isEqualByComparingTo("5.00");
        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        verify(repository).saveItem(captor.capture());
        assertThat(captor.getValue().getAnomalyThreshold()).isEqualByComparingTo("5.00");
    }

    @Test
    void updateThreshold_notOwned_throws30012() {
        when(repository.existsById(2L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 2L)).thenReturn(false);

        assertThatThrownBy(() -> service.updateItemThreshold(2L, 10L, new BigDecimal("5.00")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_FORBIDDEN);
    }

    @Test
    void updateThreshold_itemNotFound_throws30010() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(repository.findItemByWatchlistAndItemId(1L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateItemThreshold(1L, 10L, new BigDecimal("5.00")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND);
    }

    @Test
    void updateThreshold_negative_rejectedByDomain() {
        // 领域防御：负阈值在领域层即拒（接口层 @PositiveOrZero 兜底，此处直测领域不变量）
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.existsByOwnerIdAndId(ME, 1L)).thenReturn(true);
        when(repository.findItemByWatchlistAndItemId(1L, 10L))
                .thenReturn(Optional.of(item(10L, 1L, 100L, "3.00")));

        assertThatThrownBy(() -> service.updateItemThreshold(1L, 10L, new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveItem(any());
    }

    @Test
    void currentUserId_nullContext_throwsTokenInvalid() {
        UserContext.clear(); // 模拟未认证上下文（不应发生，防御性 fail-fast）
        assertThatThrownBy(() -> service.listMyWatchlists())
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    // ---- fixtures ----

    private static Watchlist watchlist(Long id, long userId, String name, WatchlistItem... items) {
        return Watchlist.reconstruct(
                id, userId, name, null, WatchlistStatus.ENABLED, List.of(items), 0L, NOW, NOW);
    }

    private static WatchlistItem item(Long id, Long watchlistId, Long subjectId, String threshold) {
        return WatchlistItem.reconstruct(
                id,
                watchlistId,
                subjectId,
                new BigDecimal(threshold),
                WatchlistStatus.ENABLED,
                0L,
                NOW,
                NOW);
    }

    private static Subject subject(Long id) {
        return Subject.reconstruct(
                id,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of(),
                "白酒",
                SubjectStatus.ENABLED,
                0L,
                NOW,
                NOW);
    }
}
