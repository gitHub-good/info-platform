package com.info.platform.application.mainline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.mainline.AttentionSource.HolderChangeSummary;
import com.info.platform.application.mainline.AttentionSource.LhbSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AttentionProxyService 单测（M27 T244，方案 §4.4.4——§6 测试要点「主力徽章降级」）：OK 徽章全字段 / 单只失败 → UNAVAILABLE 不阻塞
 * （后续只照常）/ 两报表各调一次（≤2 请求/只）。间隔注入 0——免真实 sleep。
 */
class AttentionProxyServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T10:35:00Z"), ZoneOffset.UTC);

    private AttentionSource attentionSource;

    private AttentionProxyService service;

    @BeforeEach
    void setUp() {
        attentionSource = mock(AttentionSource.class);
        service = new AttentionProxyService(attentionSource, new ObjectMapper(), FIXED_CLOCK, 0L);
    }

    @Test
    void badgesFor_ok_fullBadgeFields() {
        when(attentionSource.fetchLhbSummary(anyString(), anyString()))
                .thenReturn(new LhbSummary(3, "2026-09-01", "日涨幅偏离值达到7%的证券"));
        when(attentionSource.fetchHolderChangeSummary(anyString(), anyString()))
                .thenReturn(new HolderChangeSummary(2, 0, "净增持"));

        List<com.fasterxml.jackson.databind.node.ObjectNode> badges =
                service.badgesFor(List.of("600519"));

        assertThat(badges).hasSize(1);
        String json = badges.get(0).toString();
        assertThat(json)
                .contains("\"state\":\"OK\"")
                .contains("\"lhb30d\":3")
                .contains("\"chgDirection\":\"净增持\"")
                .contains("\"chgCount\":2")
                .contains("\"queryTime\":\"2026-09-28T10:35:00Z\"")
                .contains("日涨幅偏离值达到7%");
        // 窗口参数：sinceDate = 当日 − 30 日（Asia/Shanghai）
        verify(attentionSource).fetchLhbSummary(eq("600519"), eq("2026-08-29"));
        verify(attentionSource).fetchHolderChangeSummary(eq("600519"), eq("2026-08-29"));
    }

    @Test
    void badgesFor_failure_unavailableDoesNotBlockNext() {
        // 第一只龙虎榜失败 → UNAVAILABLE；第二只照常两报表调用（增强件旁路不阻塞榜单）
        when(attentionSource.fetchLhbSummary(anyString(), anyString()))
                .thenThrow(new IllegalStateException("datacenter 9501"))
                .thenReturn(new LhbSummary(1, null, null));
        when(attentionSource.fetchHolderChangeSummary(anyString(), anyString()))
                .thenReturn(new HolderChangeSummary(0, 0, "均衡"));

        List<com.fasterxml.jackson.databind.node.ObjectNode> badges =
                service.badgesFor(List.of("600519", "000858"));

        assertThat(badges).hasSize(2);
        assertThat(badges.get(0).path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(badges.get(1).path("state").asText()).isEqualTo("OK");
        // 第一只失败后第二只仍完整调用两只报表
        verify(attentionSource, times(2)).fetchLhbSummary(anyString(), anyString());
        verify(attentionSource, times(2)).fetchHolderChangeSummary(anyString(), anyString());
    }

    @Test
    void badgesFor_lhbOkHolderFail_unavailable() {
        when(attentionSource.fetchLhbSummary(anyString(), anyString()))
                .thenReturn(new LhbSummary(0, null, null));
        doThrow(new IllegalStateException("timeout"))
                .when(attentionSource)
                .fetchHolderChangeSummary(anyString(), anyString());

        List<com.fasterxml.jackson.databind.node.ObjectNode> badges =
                service.badgesFor(List.of("600519"));

        assertThat(badges.get(0).path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(badges.get(0).has("lhb30d")).isFalse();
    }

    @Test
    void badgesFor_emptyList_noCalls() {
        assertThat(service.badgesFor(List.of())).isEmpty();
        verify(attentionSource, times(0)).fetchLhbSummary(anyString(), anyString());
    }
}
