package com.info.platform.infrastructure.common;

import com.info.platform.domain.aggregation.SourceCode;

/**
 * 熔断器端口（按 {@link SourceCode} 分区）。
 *
 * <p>对齐方案 07 弹性四件套之「熔断」。ADR-0010：本批留接口与配置位，默认实现 {@link NoopCircuitBreaker}
 * （始终放行、不记账）；真实熔断（错误率/慢调用比例超阈值 → 自动快速失败、半开试探恢复）后续按需接入，替换 Bean 即可。
 */
public interface CircuitBreaker {

    /** 是否放行本次请求（熔断开启时返回 false，模板层直接降级不调 doFetch）。 */
    boolean allowRequest(SourceCode code);

    /** 记一次成功调用。 */
    void recordSuccess(SourceCode code);

    /** 记一次失败调用（超时/异常）。 */
    void recordFailure(SourceCode code);
}
