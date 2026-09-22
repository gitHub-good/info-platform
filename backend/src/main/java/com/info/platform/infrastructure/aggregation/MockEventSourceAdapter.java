package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
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
 */
public class MockEventSourceAdapter extends AbstractSourceAdapter {

    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(new FieldMapping("items", "items", Transform.NONE));

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
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("anomalyType", "PRICE_CHANGE");
        item.put("changePct", new BigDecimal("3.25"));
        item.put("currentPrice", new BigDecimal("1680.50"));
        item.put("triggerTime", "2026-09-21T02:00:00Z");
        item.put("detail", "日涨跌幅 3.25% 触发阈值 3.0%（现价 1680.50）(mock)");
        return Optional.of(
                new RawFetch(
                        Map.of("items", List.of(Collections.unmodifiableMap(item))),
                        sourceLabel(),
                        Instant.now()));
    }
}
