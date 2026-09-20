package com.info.platform.infrastructure.common;

import com.info.platform.domain.aggregation.SourceCode;

/**
 * 空操作熔断器：始终放行、不记账。
 *
 * <p>占位实现，满足 {@link CircuitBreaker} 契约；真实熔断（Resilience4j / 自建状态机）后续接入时替换本 Bean。
 */
public class NoopCircuitBreaker implements CircuitBreaker {

    @Override
    public boolean allowRequest(SourceCode code) {
        return true;
    }

    @Override
    public void recordSuccess(SourceCode code) {
        // no-op
    }

    @Override
    public void recordFailure(SourceCode code) {
        // no-op
    }
}
