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
 * 估值源 mock adapter（T09 骨架）。真实估值 adapter（T04）落地后设 {@code adapter.mock.enabled=false} 切换。
 *
 * <p>返回固定假数据（PE/PB/总市值），状态 OK。
 */
@Component
@ConditionalOnProperty(name = "adapter.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockValuationSourceAdapter extends AbstractSourceAdapter {

    private static final List<FieldMapping> MAPPING =
            List.of(
                    new FieldMapping("pe", "pe", Transform.TO_DECIMAL),
                    new FieldMapping("pb", "pb", Transform.TO_DECIMAL),
                    new FieldMapping("marketCap", "marketCap", Transform.TO_DECIMAL));

    public MockValuationSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        super(cache, fieldMapper, runner, breaker);
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.VALUATION;
    }

    @Override
    protected String sourceLabel() {
        return "估值源(mock)";
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
        data.put("pe", "25.50");
        data.put("pb", "8.20");
        data.put("marketCap", "2100000000000");
        return Optional.of(new RawFetch(data, "估值源(mock)", Instant.now()));
    }
}
