package com.info.platform.application.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectQuote;
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
import java.util.HashMap;
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
    private AggregationService aggregationService;
    private WatchlistService service;

    @BeforeEach
    void setUp() {
        repository = mock(WatchlistRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        aggregationService = mock(AggregationService.class);
        service = new WatchlistService(repository, subjectRepository, aggregationService);
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

    // ---- rename / delete（改名 + 软删除）----

    @Test
    void renameWatchlist_owned_newName_savesAndReturnsView() {
        Watchlist list = watchlist(1L, ME, "旧名", item(10L, 1L, 100L, "3.00"));
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 1L)).thenReturn(Optional.of(list));
        when(repository.existsByOwnerIdAndName(ME, "新名")).thenReturn(false);
        when(repository.save(any(Watchlist.class)))
                .thenReturn(watchlist(1L, ME, "新名", item(10L, 1L, 100L, "3.00")));

        WatchlistView result = service.renameWatchlist(1L, "新名");

        assertThat(result.name()).isEqualTo("新名");
        ArgumentCaptor<Watchlist> captor = ArgumentCaptor.forClass(Watchlist.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("新名");
    }

    @Test
    void renameWatchlist_sameAsCurrentName_idempotentNoConflictCheck() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 1L))
                .thenReturn(Optional.of(watchlist(1L, ME, "我的清单", item(10L, 1L, 100L, "3.00"))));

        WatchlistView result = service.renameWatchlist(1L, "我的清单");

        assertThat(result.name()).isEqualTo("我的清单");
        verify(repository, never()).existsByOwnerIdAndName(anyLong(), any());
        verify(repository, never()).save(any(Watchlist.class)); // 无变更不落库
    }

    @Test
    void renameWatchlist_conflictWithOtherEnabledList_throws30011() {
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 1L))
                .thenReturn(Optional.of(watchlist(1L, ME, "旧名")));
        when(repository.existsByOwnerIdAndName(ME, "已有名")).thenReturn(true);

        assertThatThrownBy(() -> service.renameWatchlist(1L, "已有名"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST);
        verify(repository, never()).save(any(Watchlist.class));
    }

    @Test
    void renameWatchlist_notOwned_throws30012() {
        when(repository.existsById(2L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renameWatchlist(2L, "新名"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_FORBIDDEN);
    }

    @Test
    void renameWatchlist_notExists_throws30010() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.renameWatchlist(999L, "新名"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND);
    }

    @Test
    void deleteWatchlist_owned_softDeletes() {
        Watchlist list = watchlist(1L, ME, "我的清单", item(10L, 1L, 100L, "3.00"));
        when(repository.existsById(1L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 1L)).thenReturn(Optional.of(list));

        service.deleteWatchlist(1L);

        ArgumentCaptor<Watchlist> captor = ArgumentCaptor.forClass(Watchlist.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WatchlistStatus.DELETED);
    }

    @Test
    void deleteWatchlist_notOwned_throws30012() {
        when(repository.existsById(2L)).thenReturn(true);
        when(repository.findByOwnerIdAndId(ME, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteWatchlist(2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_FORBIDDEN);
        verify(repository, never()).save(any(Watchlist.class));
    }

    @Test
    void deleteWatchlist_notExists_throws30010() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteWatchlist(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_NOT_FOUND);
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

    // ---- list items paged + sorted（分页排序）----

    /** 行情行工厂：id + 价 + 涨跌幅（null = 行情缺失行）。 */
    private static SubjectQuote quote(Long id, String price, String changePct) {
        Map<String, Object> data = new HashMap<>();
        if (price != null) {
            data.put("price", new BigDecimal(price));
        }
        if (changePct != null) {
            data.put("changePct", new BigDecimal(changePct));
        }
        Subject subject = subject(id);
        return new SubjectQuote(
                id,
                subject.getSubjectCode().value(),
                subject.getName(),
                subject.getMarket().name(),
                subject.getSubjectType().code(),
                subject.getIndustry(),
                data.isEmpty() ? null : data);
    }

    @Test
    void listItemsPaged_sortByPriceDesc_nullsLastAndPaginates() {
        // 3 项：甲(10.5)、乙(20.1)、丙(行情缺失 null) → price desc 期望 乙→甲→丙(null 沉底不随 desc 浮顶)
        Watchlist list =
                watchlist(
                        1L,
                        ME,
                        "我的清单",
                        item(10L, 1L, 100L, "3.00"),
                        item(11L, 1L, 101L, "3.00"),
                        item(12L, 1L, 102L, "3.00"));
        when(repository.findByOwnerIdAndId(ME, 1L)).thenReturn(Optional.of(list));
        when(aggregationService.getQuotes(any()))
                .thenReturn(
                        List.of(
                                quote(100L, "10.5", "-1.2"),
                                quote(101L, "20.1", "3.4"),
                                quote(102L, null, null)));

        WatchlistItemsPagedView view = service.listItemsPaged(1L, 1, 2, "price", "desc");

        assertThat(view.total()).isEqualTo(3);
        assertThat(view.items())
                .extracting(WatchlistItemPagedRow::subjectId)
                .containsExactly(101L, 100L); // 第 2 页（丙）未取
        assertThat(view.items().get(0).price()).isEqualByComparingTo("20.1");
        assertThat(view.items().get(0).changePct()).isEqualByComparingTo("3.4");
        assertThat(view.sort()).isEqualTo("price");
        assertThat(view.dir()).isEqualTo("desc");

        WatchlistItemsPagedView page2 = service.listItemsPaged(1L, 2, 2, "price", "desc");
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).price()).isNull(); // 行情缺失行沉底
        assertThat(page2.items().get(0).subjectCode()).isNotNull(); // 摘要仍内联
    }

    @Test
    void listItemsPaged_sortByChangePctAsc_ordered() {
        Watchlist list =
                watchlist(1L, ME, "我的清单", item(10L, 1L, 100L, "3.00"), item(11L, 1L, 101L, "3.00"));
        when(repository.findByOwnerIdAndId(ME, 1L)).thenReturn(Optional.of(list));
        when(aggregationService.getQuotes(any()))
                .thenReturn(List.of(quote(100L, "10.5", "-1.2"), quote(101L, "20.1", "3.4")));

        WatchlistItemsPagedView view = service.listItemsPaged(1L, 1, 20, "changePct", "asc");

        assertThat(view.items())
                .extracting(WatchlistItemPagedRow::subjectId)
                .containsExactly(100L, 101L);
    }

    @Test
    void listItemsPaged_defaultSort_addedAtAscKeepsInsertionOrder() {
        Watchlist list =
                watchlist(1L, ME, "我的清单", item(10L, 1L, 100L, "3.00"), item(11L, 1L, 101L, "3.00"));
        when(repository.findByOwnerIdAndId(ME, 1L)).thenReturn(Optional.of(list));
        when(aggregationService.getQuotes(any())).thenReturn(List.of());

        WatchlistItemsPagedView view = service.listItemsPaged(1L, 1, 20, "addedAt", "asc");

        // 行情整体缺失：排序键非行情时不受 null 沉底影响，保持加入顺序
        assertThat(view.items()).extracting(WatchlistItemPagedRow::id).containsExactly(10L, 11L);
        assertThat(view.items().get(0).price()).isNull();
        assertThat(view.items().get(0).subjectCode()).isNull(); // 摘要随行情同源缺失
    }

    @Test
    void listItemsPaged_emptyItems_returnsEmptyViewWithoutQuoteFetch() {
        when(repository.findByOwnerIdAndId(ME, 1L))
                .thenReturn(Optional.of(watchlist(1L, ME, "空清单")));

        WatchlistItemsPagedView view = service.listItemsPaged(1L, 1, 20, "price", "desc");

        assertThat(view.total()).isZero();
        assertThat(view.items()).isEmpty();
        verify(aggregationService, never()).getQuotes(any());
    }

    @Test
    void listItemsPaged_invalidSort_throwsParamInvalid() {
        assertThatThrownBy(() -> service.listItemsPaged(1L, 1, 20, "name", "asc"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void listItemsPaged_invalidDir_throwsParamInvalid() {
        assertThatThrownBy(() -> service.listItemsPaged(1L, 1, 20, "price", "up"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void listItemsPaged_notOwned_throws30012() {
        when(repository.findByOwnerIdAndId(ME, 2L)).thenReturn(Optional.empty());
        when(repository.existsById(2L)).thenReturn(true);

        assertThatThrownBy(() -> service.listItemsPaged(2L, 1, 20, "price", "desc"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATCHLIST_FORBIDDEN);
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
