package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 估值源 mock adapter（T09 骨架；T36 起经 {@link RoutingSourceAdapter} 按 {@code datasource.VALUATION.mode} 路由生效）。
 *
 * <p>返回固定假数据（PE/PB/总市值），状态 OK。
 */
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

    /** T31：与真实 ValuationSourceAdapter 对齐（PE/PB 个股估值语义），mock/real 支持范围一致。 */
    @Override
    public Set<SubjectType> supportedSubjectTypes() {
        return EnumSet.of(SubjectType.STOCK);
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
    protected Optional<RawFetch> doFetch(Subject subject) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pe", "25.50");
        data.put("pb", "8.20");
        data.put("marketCap", "2100000000000");
        return Optional.of(new RawFetch(data, "估值源(mock)", Instant.now()));
    }
}
