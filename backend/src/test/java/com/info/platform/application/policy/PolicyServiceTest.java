package com.info.platform.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyListFilter;
import com.info.platform.domain.policy.PolicyRepository;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PolicyService 单元测试（T24）：mock PolicyRepository/WatchlistRepository/SubjectRepository，验证列表分页/行业过滤/
 * 详情+关联自选标的（按 industry 匹配）/ai_tendency=0/404。 不依赖真实 DB 与 gov.cn（对齐 04 测试规范 FIRST）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyServiceTest {

    @Mock private PolicyRepository policyRepository;
    @Mock private WatchlistRepository watchlistRepository;
    @Mock private SubjectRepository subjectRepository;

    private PolicyService service;

    @BeforeEach
    void setUp() {
        service = new PolicyService(policyRepository, watchlistRepository, subjectRepository);
    }

    private static PolicyItem policy(Long id, String title, List<String> industries) {
        return PolicyItem.reconstruct(
                id,
                title,
                "国务院政策",
                LocalDate.of(2026, 9, 20),
                null,
                industries,
                AiTendency.UNJUDGED,
                "https://gov/" + id,
                Instant.parse("2026-09-21T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"));
    }

    private static Subject subject(Long id, String code, String name, String industry) {
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
                null,
                null);
    }

    private static Watchlist watchlistWith(WatchlistItem... items) {
        return Watchlist.reconstruct(
                1L, 1L, "默认清单", null, WatchlistStatus.ENABLED, List.of(items), 0L, null, null);
    }

    @Test
    void listPolicies_fullPage_returnsItemsAndNextCursor() {
        // Arrange：repo 返回满页 20 条 → nextCursor = 末条 id
        java.util.List<PolicyItem> items = new java.util.ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            items.add(policy(i, "政策" + i, List.of("白酒")));
        }
        when(policyRepository.findRecent(7, null, null, 20)).thenReturn(items);

        // Act
        PolicyListView view = service.listPolicies(7, null, null);

        // Assert
        assertThat(view.policies()).hasSize(20);
        assertThat(view.nextCursor()).isEqualTo(20L);
        assertThat(view.policies().get(0).title()).isEqualTo("政策1");
        assertThat(view.policies().get(0).relatedIndustries()).containsExactly("白酒");
        assertThat(view.policies().get(0).publishedAt()).isEqualTo("2026-09-20");
    }

    @Test
    void listPolicies_partialPage_nextCursorNull() {
        // Arrange：不满一页 → nextCursor=null（无下一页）
        when(policyRepository.findRecent(7, null, null, 20))
                .thenReturn(List.of(policy(1L, "政策1", List.of())));

        // Act + Assert
        PolicyListView view = service.listPolicies(7, null, null);
        assertThat(view.policies()).hasSize(1);
        assertThat(view.nextCursor()).isNull();
    }

    @Test
    void listPolicies_industryTrimmedAndPassedToRepo() {
        // Arrange：industry 前后空格被 trim 后透传 repo（行业过滤契约）
        when(policyRepository.findRecent(7, "银行", null, 20))
                .thenReturn(List.of(policy(5L, "降准政策", List.of("银行"))));

        // Act
        PolicyListView view = service.listPolicies(7, "  银行 ", null);

        // Assert
        assertThat(view.policies()).hasSize(1);
        assertThat(view.policies().get(0).relatedIndustries()).containsExactly("银行");
    }

    @Test
    void listPolicies_cursorPassedToRepo() {
        // Arrange：翻页游标透传 repo
        when(policyRepository.findRecent(7, null, 30L, 20))
                .thenReturn(List.of(policy(29L, "政策29", List.of())));

        // Act + Assert
        PolicyListView view = service.listPolicies(7, null, 30L);
        assertThat(view.policies()).hasSize(1);
        assertThat(view.nextCursor()).isNull();
    }

    // ==================== M9 T60：页码模式 ====================

    @Test
    void listPoliciesPaged_countAndPageSameFilter_returnsTotalAndEcho() {
        // Arrange：同一 filter 走 count + findPage；industry trim 后透传
        PolicyListFilter filter = new PolicyListFilter(7, "银行");
        when(policyRepository.countByFilter(filter)).thenReturn(45L);
        when(policyRepository.findPage(filter, 2, 20))
                .thenReturn(List.of(policy(44L, "政策44", List.of("银行"))));

        // Act
        PolicyPagedView view = service.listPoliciesPaged(7, "  银行 ", 2, 20);

        // Assert：total 精确回显、page/size 如实回显、列表字段与游标模式一致
        assertThat(view.total()).isEqualTo(45L);
        assertThat(view.page()).isEqualTo(2);
        assertThat(view.size()).isEqualTo(20);
        assertThat(view.policies()).hasSize(1);
        assertThat(view.policies().get(0).title()).isEqualTo("政策44");
        assertThat(view.policies().get(0).relatedIndustries()).containsExactly("银行");
    }

    @Test
    void listPoliciesPaged_outOfRangePage_emptyListWithRealTotal() {
        // Arrange：越界页（offset 超总数）→ 空列表 + 真实 total（ADR-0035：200 + 空列表 + 如实回显）
        PolicyListFilter filter = new PolicyListFilter(7, null);
        when(policyRepository.countByFilter(filter)).thenReturn(8L);
        when(policyRepository.findPage(filter, 99, 20)).thenReturn(List.of());

        // Act + Assert
        PolicyPagedView view = service.listPoliciesPaged(7, null, 99, 20);
        assertThat(view.policies()).isEmpty();
        assertThat(view.total()).isEqualTo(8L);
        assertThat(view.page()).isEqualTo(99);
        assertThat(view.size()).isEqualTo(20);
    }

    @Test
    void listPoliciesPaged_daysPassedThroughToFilter() {
        // Arrange：days 透传 filter（时间窗语义由 repo 层 clamp，service 不改写）
        PolicyListFilter filter = new PolicyListFilter(30, null);
        when(policyRepository.countByFilter(filter)).thenReturn(0L);
        when(policyRepository.findPage(filter, 1, 10)).thenReturn(List.of());

        // Act + Assert
        PolicyPagedView view = service.listPoliciesPaged(30, null, 1, 10);
        assertThat(view.total()).isZero();
        assertThat(view.policies()).isEmpty();
    }

    @Test
    void getPolicy_notFound_throws30040() {
        // Arrange
        when(policyRepository.findById(999L)).thenReturn(Optional.empty());

        // Act + Assert：抛 BusinessException(POLICY_NOT_FOUND)，对齐 §4.1.5 错误码 30040
        assertThatThrownBy(() -> service.getPolicy(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.POLICY_NOT_FOUND);
    }

    @Test
    void getPolicy_foundWithRelatedSubjects_aiTendencyZero() {
        // Arrange：政策关联白酒+银行；用户 watchlist 含茅台(白酒)+招行(银行)
        PolicyItem item = policy(1L, "国务院关于白酒与银行的意见", List.of("白酒", "银行"));
        when(policyRepository.findById(1L)).thenReturn(Optional.of(item));
        Subject moutai = subject(600519L, "SH600519", "贵州茅台", "白酒");
        Subject cmb = subject(600036L, "SH600036", "招商银行", "银行");
        when(watchlistRepository.findAllByOwnerId(1L))
                .thenReturn(
                        List.of(
                                watchlistWith(
                                        WatchlistItem.create(1L, 600519L, new BigDecimal("3.00")),
                                        WatchlistItem.create(
                                                2L, 600036L, new BigDecimal("3.00")))));
        when(subjectRepository.findById(600519L)).thenReturn(Optional.of(moutai));
        when(subjectRepository.findById(600036L)).thenReturn(Optional.of(cmb));

        // Act
        PolicyDetailView detail = service.getPolicy(1L, 1L);

        // Assert：ai_tendency=0 未判（T28 前）；关联自选标的 2 只（白酒+银行各一）
        assertThat(detail.aiTendency()).isZero();
        assertThat(detail.relatedIndustries()).containsExactlyInAnyOrder("白酒", "银行");
        assertThat(detail.relatedSubjects()).hasSize(2);
        assertThat(detail.relatedSubjects())
                .extracting(RelatedSubjectView::subjectCode)
                .containsExactlyInAnyOrder("SH600519", "SH600036");
        assertThat(detail.relatedSubjects())
                .extracting(RelatedSubjectView::industry)
                .containsExactlyInAnyOrder("白酒", "银行");
    }

    @Test
    void getPolicy_industryNotMatch_emptyRelatedSubjects() {
        // Arrange：政策关联互联网；用户只有白酒标的 → 无匹配
        PolicyItem item = policy(1L, "互联网政策", List.of("互联网"));
        when(policyRepository.findById(1L)).thenReturn(Optional.of(item));
        Subject moutai = subject(600519L, "SH600519", "贵州茅台", "白酒");
        when(watchlistRepository.findAllByOwnerId(1L))
                .thenReturn(
                        List.of(
                                watchlistWith(
                                        WatchlistItem.create(
                                                1L, 600519L, new BigDecimal("3.00")))));
        when(subjectRepository.findById(600519L)).thenReturn(Optional.of(moutai));

        // Act + Assert
        PolicyDetailView detail = service.getPolicy(1L, 1L);
        assertThat(detail.relatedSubjects()).isEmpty();
    }

    @Test
    void getPolicy_emptyRelatedIndustries_skipsWatchlist() {
        // Arrange：政策无关联行业 → 直接返回空，不查 watchlist/subject（短路）
        PolicyItem item = policy(1L, "无行业关联政策", List.of());
        when(policyRepository.findById(1L)).thenReturn(Optional.of(item));

        // Act
        PolicyDetailView detail = service.getPolicy(1L, 1L);

        // Assert：无关联行业不触发 watchlist 查询
        assertThat(detail.relatedSubjects()).isEmpty();
        verifyNoInteractions(watchlistRepository);
        verifyNoInteractions(subjectRepository);
    }

    @Test
    void getPolicy_subjectMissingIndustry_notLinked() {
        // Arrange：标的 industry=null（未录行业）→ 不关联
        PolicyItem item = policy(1L, "白酒政策", List.of("白酒"));
        when(policyRepository.findById(1L)).thenReturn(Optional.of(item));
        Subject noIndustry = subject(600519L, "SH600519", "贵州茅台", null);
        when(watchlistRepository.findAllByOwnerId(1L))
                .thenReturn(
                        List.of(
                                watchlistWith(
                                        WatchlistItem.create(
                                                1L, 600519L, new BigDecimal("3.00")))));
        when(subjectRepository.findById(600519L)).thenReturn(Optional.of(noIndustry));

        // Act + Assert
        PolicyDetailView detail = service.getPolicy(1L, 1L);
        assertThat(detail.relatedSubjects()).isEmpty();
    }
}
