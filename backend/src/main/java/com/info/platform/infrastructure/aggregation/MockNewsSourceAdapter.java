package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
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
 * 新闻源 mock adapter（T09 骨架，验证 FAILED 降级路径）。
 *
 * <p>真实新闻 adapter（T06）落地后设 {@code adapter.mock.enabled=false} 切换。 本 mock 的 doFetch 抛异常， 经模板弹性降级后
 * {@link #onDegraded} 覆写为 {@link SourceResult#failed}（默认返回 MISSING，此处演示 FAILED 语义），
 * 验证「单源异常不阻断其他分区」。
 */
@Component
@ConditionalOnProperty(name = "adapter.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockNewsSourceAdapter extends AbstractSourceAdapter {

    public MockNewsSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        super(cache, fieldMapper, runner, breaker);
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.NEWS;
    }

    @Override
    protected String sourceLabel() {
        return "新闻源(mock)";
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
        throw new RuntimeException("新闻源暂时不可用(mock)");
    }

    @Override
    protected SourceResult onDegraded(Subject subject, String reason) {
        return SourceResult.failed(SourceCode.NEWS, subject.getId(), "新闻源(mock)");
    }
}
