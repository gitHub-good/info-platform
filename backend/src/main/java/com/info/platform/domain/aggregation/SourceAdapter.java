package com.info.platform.domain.aggregation;

/**
 * 数据源适配器端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>每源一个实现（T03~T08）。基础设施层 {@code AbstractSourceAdapter} 提供模板方法，编排缓存命中、 弹性（超时/重试/熔断）、字段映射与降级；子类只实现
 * doFetch 与映射配置。本接口保持领域层纯净，不依赖任何框架类型。
 *
 * <p>对齐技术方案 §4.4「统一数据访问层」与 ADR-0003。
 */
public interface SourceAdapter {

    /**
     * 取某标的在本源的数据。
     *
     * <p>结果含状态（OK/MISSING/FAILED）：单源缺失/异常不抛出，由调用方（应用层聚合服务）按 sourceStatus 分区兜底， 不阻断其他源。缓存命中直接返回。
     */
    SourceResult fetch(Subject subject);

    /** 本源标识（六类之一），用作缓存/限频/熔断分区键。 */
    SourceCode sourceCode();
}
