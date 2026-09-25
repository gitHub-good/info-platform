package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事件源 mock adapter（T09 骨架）：返回一条样例异动，验证 EVENT 分区 OK 链路（列表项经 data.items 承载）。
 *
 * <p>T36 起经 {@link RoutingSourceAdapter} 按 {@code datasource.EVENT.mode} 路由生效。 本 mock 取数路径与 {@link
 * EventSourceAdapter#doFetch} 同构（构建 items 列表包装进 {@code RawFetch.data}）， 仅数据源换为硬编码样例。
 *
 * <p>M12 T91：{@code doFetch} 附带 {@code total}（与真实 adapter 契约对齐，sectionPagination 首屏总数在 mock
 * 模式下同样可提取）；{@code fetchPage} 覆写为全量条目窗口切片 + total=条数（PRD 场景 5「mock 下不报错」口径）。
 */
public class MockEventSourceAdapter extends AbstractSourceAdapter {

    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(
                    new FieldMapping("items", "items", Transform.NONE),
                    new FieldMapping("total", "total", Transform.NONE));

    /** 本地调用超时：与真实事件源同口径（Spike-1 §6.7：noRetry 500ms）。 */
    public MockEventSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        super(cache, fieldMapper, runner, breaker);
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.EVENT;
    }

    @Override
    protected String sourceLabel() {
        return "事件监控(mock)";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return ITEMS_PASSTHROUGH;
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        return Optional.of(pageFetch(allItems(), allItems().size()));
    }

    /** mock 分页：全量条目按 page/size 切片 + total=全量条数（不模拟真实深翻）。 */
    @Override
    public SourceResult fetchPage(Subject subject, int page, int size) {
        return runGuarded(
                subject,
                () -> Optional.of(pageFetch(slicePage(allItems(), page, size), allItems().size())));
    }

    private static List<Map<String, Object>> allItems() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("anomalyType", "PRICE_CHANGE");
        item.put("changePct", new BigDecimal("3.25"));
        item.put("currentPrice", new BigDecimal("1680.50"));
        item.put("triggerTime", "2026-09-21T02:00:00Z");
        item.put("detail", "日涨跌幅 3.25% 触发阈值 3.0%（现价 1680.50）(mock)");
        return List.of(Collections.unmodifiableMap(item));
    }

    private static List<Map<String, Object>> slicePage(
            List<Map<String, Object>> items, int page, int size) {
        int from = Math.min((page - 1) * size, items.size());
        int to = Math.min(from + size, items.size());
        return items.subList(from, to);
    }

    private static RawFetch pageFetch(List<Map<String, Object>> items, long total) {
        return new RawFetch(
                Map.of("items", List.copyOf(items), "total", total),
                sourceLabelOfMock(),
                Instant.now());
    }

    private static String sourceLabelOfMock() {
        return "事件监控(mock)";
    }
}
