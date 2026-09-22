package com.info.platform.application.aggregation;

/**
 * 聚合编排运行时设置端口（T36，方案 §4.3「AggregationService 总超时每请求读 aggregation.global」）。
 *
 * <p>端口在应用层、实现由基础设施层按 {@code ConfigCenter} 快照提供——应用层直接依赖 {@code ConfigCenter}（基础设施）会与既有
 * infrastructure→application 依赖成环（LayeredArchitectureTest 层切片无环约束）， 故走端口倒置（同 {@code
 * LlmConfigFacade} 先例）。消费点<b>每请求读取</b>即热生效（LIVE 级，PRD 场景 3.3 调总超时即生效）。
 */
public interface AggregationRuntimeSettings {

    /** 详情页聚合总超时（毫秒，{@code aggregation.global.detailTimeoutMillis}，缺省回落 yml 值）。 */
    long detailTimeoutMillis();
}
