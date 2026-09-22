package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 7 个 MockSourceAdapter
 * 契约测试（T09）：验证混合状态（QUOTE/FINANCE/VALUATION/ANNOUNCE/EVENT=OK、NEWS=FAILED、POLICY=MISSING） 与
 * FieldMapper JSON 映射链路。纯单元：直接构造 adapter + 真实虚拟线程执行器（真实超时，非 mock）。
 */
class MockSourceAdaptersTest {

    private SourceCache cache;
    private FieldMapper fieldMapper;
    private ResilienceRunner runner;
    private NoopCircuitBreaker breaker;
    private ExecutorService exec;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        cache = new SourceCache();
        fieldMapper = new FieldMapper(new ObjectMapper());
        runner = new ResilienceRunner(exec);
        breaker = new NoopCircuitBreaker();
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    @Test
    void quoteAdapter_returnsOkWithJsonMappedFields() {
        MockQuoteSourceAdapter adapter =
                new MockQuoteSourceAdapter(cache, fieldMapper, runner, breaker);

        SourceResult result = adapter.fetch(subject(1L));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.QUOTE);
        assertThat(result.getData().get("price")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) result.getData().get("price"))
                .isEqualByComparingTo(new BigDecimal("1680.50"));
        assertThat((BigDecimal) result.getData().get("changePct"))
                .isEqualByComparingTo(new BigDecimal("1.20"));
        assertThat(result.getData().get("volume")).isInstanceOf(Long.class);
        assertThat(result.getData().get("tradeDate")).isEqualTo("2026-09-20");
    }

    @Test
    void financeAdapter_returnsOkWithDecimalFields() {
        MockFinanceSourceAdapter adapter =
                new MockFinanceSourceAdapter(cache, fieldMapper, runner, breaker);

        SourceResult result = adapter.fetch(subject(1L));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getData().get("netProfit")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) result.getData().get("revenue"))
                .isEqualByComparingTo(new BigDecimal("127560000000.00"));
    }

    @Test
    void valuationAdapter_returnsOkWithDecimalFields() {
        MockValuationSourceAdapter adapter =
                new MockValuationSourceAdapter(cache, fieldMapper, runner, breaker);

        SourceResult result = adapter.fetch(subject(1L));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat((BigDecimal) result.getData().get("pe"))
                .isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat((BigDecimal) result.getData().get("pb"))
                .isEqualByComparingTo(new BigDecimal("8.20"));
    }

    @Test
    void announceAdapter_returnsOkWithItemsList() {
        MockAnnounceSourceAdapter adapter =
                new MockAnnounceSourceAdapter(cache, fieldMapper, runner, breaker);

        SourceResult result = adapter.fetch(subject(1L));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        Object items = result.getData().get("items");
        assertThat(items).isInstanceOf(List.class);
        assertThat((List<?>) items).hasSize(2);
    }

    @Test
    void newsAdapter_throwsAndDegradesToFailed() {
        MockNewsSourceAdapter adapter =
                new MockNewsSourceAdapter(cache, fieldMapper, runner, breaker);

        SourceResult result = adapter.fetch(subject(1L));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.FAILED);
        assertThat(result.getData()).isEmpty();
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.NEWS);
    }

    @Test
    void policyAdapter_returnsEmptyAndDegradesToMissing() {
        MockPolicySourceAdapter adapter =
                new MockPolicySourceAdapter(cache, fieldMapper, runner, breaker);

        SourceResult result = adapter.fetch(subject(1L));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
        assertThat(result.getSource()).isEqualTo("政策源(mock)");
    }

    @Test
    void eventAdapter_returnsOkWithSampleItems() {
        // T08（ADR-0013）：事件 mock 走 data.items 列表契约，供 mock 模式下详情页事件分区展示
        MockEventSourceAdapter adapter =
                new MockEventSourceAdapter(cache, fieldMapper, runner, breaker);

        SourceResult result = adapter.fetch(subject(1L));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.EVENT);
        assertThat(result.getSource()).isEqualTo("事件监控(mock)");
        Object items = result.getData().get("items");
        assertThat(items).isInstanceOf(List.class);
        assertThat((List<?>) items).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) items).get(0);
        assertThat(first.get("anomalyType")).isEqualTo("PRICE_CHANGE");
        assertThat(first.get("triggerTime")).isEqualTo("2026-09-21T02:00:00Z");
    }

    private static Subject subject(Long id) {
        return Subject.reconstruct(
                id,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of("tushare", "600519.SH"),
                "白酒",
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}
