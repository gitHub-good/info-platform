package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 财务源 mock adapter（T09 骨架）。真实财务 adapter（T04）落地后设 {@code adapter.mock.enabled=false} 切换。
 *
 * <p>返回固定假数据（净利润/营收/EPS/ROE），状态 OK。映射配置直接构造（identity + to_decimal）。
 */
@Component
@ConditionalOnProperty(name = "adapter.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockFinanceSourceAdapter extends AbstractSourceAdapter {

    private static final List<FieldMapping> MAPPING =
            List.of(
                    new FieldMapping("netProfit", "netProfit", Transform.TO_DECIMAL),
                    new FieldMapping("revenue", "revenue", Transform.TO_DECIMAL),
                    new FieldMapping("eps", "eps", Transform.TO_DECIMAL),
                    new FieldMapping("roe", "roe", Transform.TO_DECIMAL));

    public MockFinanceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        super(cache, fieldMapper, runner, breaker);
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.FINANCE;
    }

    @Override
    protected String sourceLabel() {
        return "财务源(mock)";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return MAPPING;
    }

    @Override
    protected ResilienceSpec resilienceSpec() {
        return ResilienceSpec.noRetry(Duration.ofSeconds(2));
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("netProfit", "86280000000.00");
        data.put("revenue", "127560000000.00");
        data.put("eps", "68.76");
        data.put("roe", "30.55");
        return Optional.of(new RawFetch(data, "财务源(mock)", Instant.now()));
    }
}
