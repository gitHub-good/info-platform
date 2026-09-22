package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 行情源 mock adapter（T09 骨架，验证 FieldMapper JSON 映射链路）。
 *
 * <p>T36 起与真实行情 adapter 经 {@link RoutingSourceAdapter} 运行时路由共存（{@code datasource.QUOTE.mode=MOCK}
 * 时本类生效）。 本 mock 返回固定假数据（开高低收量额），状态 OK。映射配置走 classpath JSON（{@code
 * field-mapping/quote-mock.json}），验证 to_decimal/to_long/to_iso_date 转换。
 */
public class MockQuoteSourceAdapter extends AbstractSourceAdapter {

    private final List<FieldMapping> mapping;

    public MockQuoteSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        super(cache, fieldMapper, runner, breaker);
        this.mapping = fieldMapper.loadMapping("field-mapping/quote-mock.json");
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.QUOTE;
    }

    @Override
    protected String sourceLabel() {
        return "行情源(mock)";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return mapping;
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("price", "1680.50");
        data.put("pct_chg", "1.20");
        data.put("open", "1670.00");
        data.put("high", "1690.00");
        data.put("low", "1665.00");
        data.put("close", "1680.50");
        data.put("vol", "123456");
        data.put("amount", "9876543210");
        data.put("trade_date", "2026-09-20");
        return Optional.of(new RawFetch(data, "行情源(mock)", Instant.now()));
    }
}
