package com.info.platform.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.policy.PolicyScopeQueryService.PolicyScopePage;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeFilter;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PolicyService 单元测试（V2.3-M23 T201 数据面切换）：mock PolicyScopeQueryService/EventItemRepository—— 游标模式
 * keyset/nextCursor、页码模式 total 回显与 sourceCode 透传、详情 30040/matchedSubjects 解析/relatedEvents 承接、
 * <b>policy_item 零读取</b>（V2.3 T203 起轨 B 整包删除，零读取由类缺失构造性保证——见 PolicyTrackRetirementGate3Test）。 不依赖真实
 * DB 与 gov.cn（FIRST）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyServiceTest {

    @Mock private PolicyScopeQueryService policyScope;
    @Mock private EventItemRepository eventItemRepository;

    private PolicyService service;

    @BeforeEach
    void setUp() {
        service = new PolicyService(policyScope, eventItemRepository, new ObjectMapper());
    }

    private static PolicyScopeRow row(long newsId, String title, String matchedJson) {
        return new PolicyScopeRow(
                newsId,
                title,
                "摘要" + newsId,
                "https://gov/" + newsId,
                Instant.parse("2026-09-20T05:00:00Z"),
                "gov_policy",
                "中国政府网·政策",
                "政策",
                "监管·政策",
                null,
                matchedJson);
    }

    private static EventItem policyEvent(long newsId) {
        return EventItem.reconstruct(
                9L,
                newsId,
                EventType.POLICY_RELEASE,
                "央行降准释放流动性",
                List.of("银行"),
                Direction.BULLISH,
                Importance.HIGH,
                List.of(),
                List.of(),
                null,
                Instant.parse("2026-09-20T05:00:00Z"),
                "2026-09-20",
                "policy-v1",
                null,
                null);
    }

    @Test
    void listPolicies_fullPage_returnsItemsAndNextCursorFromNewsIds() {
        // Arrange：满页 20 条 → nextCursor = 末条 news id（游标随数据面换血，ADR-0062）
        List<PolicyScopeRow> rows = new java.util.ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            rows.add(row(i, "政策" + i, null));
        }
        when(policyScope.list(any())).thenReturn(new PolicyScopePage(rows, 20));

        // Act
        PolicyListView view = service.listPolicies(7, null, null);

        // Assert
        assertThat(view.policies()).hasSize(20);
        assertThat(view.nextCursor()).isEqualTo(20L);
        assertThat(view.basis()).isEqualTo("policy-scope-v1");
        assertThat(view.policies().get(0).id()).isEqualTo(1L);
        assertThat(view.policies().get(0).sourceCode()).isEqualTo("gov_policy");
        assertThat(view.policies().get(0).publishedAt()).isEqualTo("2026-09-20T05:00:00Z");
        assertThat(view.policies().get(0).mainCategory()).isEqualTo("监管·政策");
    }

    @Test
    void listPolicies_partialPage_nextCursorNull() {
        when(policyScope.list(any()))
                .thenReturn(new PolicyScopePage(List.of(row(1L, "政策1", null)), 1));

        PolicyListView view = service.listPolicies(7, null, null);
        assertThat(view.policies()).hasSize(1);
        assertThat(view.nextCursor()).isNull();
    }

    @Test
    void listPolicies_cursorPassedAsKeysetBeforeId() {
        // Arrange：cursor=30 → beforeId=30 透传读口（keyset 续取）
        when(policyScope.list(any()))
                .thenReturn(new PolicyScopePage(List.of(row(29L, "政策29", null)), 1));

        // Act
        service.listPolicies(7, null, 30L);

        // Assert：filter 携带 beforeId
        org.mockito.ArgumentCaptor<PolicyScopeFilter> captor =
                org.mockito.ArgumentCaptor.forClass(PolicyScopeFilter.class);
        org.mockito.Mockito.verify(policyScope).list(captor.capture());
        assertThat(captor.getValue().beforeId()).isEqualTo(30L);
    }

    @Test
    void listPoliciesPaged_totalAndEcho_sourceCodePassedThrough() {
        // Arrange：sourceCode 显式选源透传（宏观源旁路口径）
        when(policyScope.list(any()))
                .thenReturn(new PolicyScopePage(List.of(row(44L, "政策44", null)), 45));

        // Act
        PolicyPagedView view = service.listPoliciesPaged(7, null, null, "stats_release", 2, 20);

        // Assert：total 精确回显、page/size 如实回显、basis 版本化
        assertThat(view.total()).isEqualTo(45L);
        assertThat(view.page()).isEqualTo(2);
        assertThat(view.size()).isEqualTo(20);
        assertThat(view.basis()).isEqualTo("policy-scope-v1");
        org.mockito.ArgumentCaptor<PolicyScopeFilter> captor =
                org.mockito.ArgumentCaptor.forClass(PolicyScopeFilter.class);
        org.mockito.Mockito.verify(policyScope).list(captor.capture());
        assertThat(captor.getValue().sourceCode()).isEqualTo("stats_release");
        assertThat(captor.getValue().offset()).isEqualTo(20);
    }

    @Test
    void listPoliciesPaged_outOfRangePage_emptyListWithRealTotal() {
        when(policyScope.list(any())).thenReturn(new PolicyScopePage(List.of(), 8));

        PolicyPagedView view = service.listPoliciesPaged(7, null, null, null, 99, 20);
        assertThat(view.policies()).isEmpty();
        assertThat(view.total()).isEqualTo(8L);
        assertThat(view.page()).isEqualTo(99);
        assertThat(view.size()).isEqualTo(20);
    }

    @Test
    void getPolicy_outOfScopeOrMissing_throws30040() {
        when(policyScope.findByNewsId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPolicy(999L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.POLICY_NOT_FOUND);
    }

    @Test
    void getPolicy_found_matchedSubjectsParsedAndRelatedEventCarried() {
        // Arrange：matched_subjects 回联 1 标的 + 关联 L2 政策发布事件（direction 承接倾向）
        when(policyScope.findByNewsId(1L))
                .thenReturn(
                        Optional.of(
                                row(
                                        1L,
                                        "降准政策",
                                        "[{\"code\":\"SH600519\",\"name\":\"贵州茅台\",\"industry\":\"白酒\"}]")));
        when(eventItemRepository.findByNewsId(1L)).thenReturn(Optional.of(policyEvent(1L)));

        // Act
        PolicyDetailView detail = service.getPolicy(1L);

        // Assert：matchedSubjects 库内直读 + relatedEvents 至多一条（UNIQUE news_id）
        assertThat(detail.matchedSubjects()).hasSize(1);
        assertThat(detail.matchedSubjects().get(0).code()).isEqualTo("SH600519");
        assertThat(detail.matchedSubjects().get(0).industry()).isEqualTo("白酒");
        assertThat(detail.relatedEvents()).hasSize(1);
        assertThat(detail.relatedEvents().get(0).direction()).isEqualTo("BULLISH");
        assertThat(detail.relatedEvents().get(0).eventType()).isEqualTo("POLICY_RELEASE");
        assertThat(detail.relatedEvents().get(0).eventDate()).isEqualTo("2026-09-20");
    }

    @Test
    void getPolicy_noEvent_emptyRelatedEvents() {
        when(policyScope.findByNewsId(2L)).thenReturn(Optional.of(row(2L, "无事件政策", "[]")));
        when(eventItemRepository.findByNewsId(2L)).thenReturn(Optional.empty());

        PolicyDetailView detail = service.getPolicy(2L);

        assertThat(detail.relatedEvents()).isEmpty();
        assertThat(detail.matchedSubjects()).isEmpty();
    }

    @Test
    void getPolicy_corruptedMatchedSubjectsJson_degradesToEmptyList() {
        // 损坏容错：回联列非权威面，解析失败降级空表不阻断详情
        when(policyScope.findByNewsId(3L))
                .thenReturn(Optional.of(row(3L, "坏 JSON 政策", "not-json")));
        when(eventItemRepository.findByNewsId(3L)).thenReturn(Optional.empty());

        PolicyDetailView detail = service.getPolicy(3L);

        assertThat(detail.matchedSubjects()).isEmpty();
    }

    @Test
    void policyService_policyItemZeroRead_structuralAfterRetirement() {
        // Gate 2 → Gate 3 演进（T203 轨 B 整包删除后）：零 policy_item 读取由「写/读链类不存在」构造性保证——
        // 运行类路径无 domain.policy.PolicyRepository（静态断言，与 PolicyTrackRetirementGate3Test 同源）
        assertThat(classExists("com.info.platform.domain.policy.PolicyRepository")).isFalse();
    }

    private static boolean classExists(String fqcn) {
        try {
            Class.forName(fqcn, false, PolicyServiceTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
