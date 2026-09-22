package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.util.List;
import java.util.Optional;

/**
 * 政策源 mock adapter（T09 骨架，验证 MISSING 降级路径）。
 *
 * <p>T36 起经 {@link RoutingSourceAdapter} 按 {@code datasource.POLICY.mode} 路由生效。 本 mock 的 doFetch 返回
 * {@code Optional.empty()}，表示源当日无数据，模板映射为 {@link
 * com.info.platform.domain.aggregation.SourceStatus#MISSING}， 验证「单源缺失不阻断其他分区」。
 */
public class MockPolicySourceAdapter extends AbstractSourceAdapter {

    public MockPolicySourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        super(cache, fieldMapper, runner, breaker);
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.POLICY;
    }

    @Override
    protected String sourceLabel() {
        return "政策源(mock)";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return List.of();
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        return Optional.empty();
    }
}
