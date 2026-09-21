package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 政策源 mock adapter（T09 骨架，验证 MISSING 降级路径）。
 *
 * <p>真实政策 adapter（T07，RSS 接入）落地后设 {@code adapter.mock.enabled=false} 切换。 本 mock 的 doFetch 返回 {@code
 * Optional.empty()}，表示源当日无数据，模板映射为 {@link
 * com.info.platform.domain.aggregation.SourceStatus#MISSING}， 验证「单源缺失不阻断其他分区」。
 */
@Component
@ConditionalOnProperty(name = "adapter.mock.enabled", havingValue = "true", matchIfMissing = true)
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
    protected ResilienceSpec resilienceSpec() {
        return ResilienceSpec.noRetry(Duration.ofSeconds(2));
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        return Optional.empty();
    }
}
