package com.info.platform.application.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.common.BusinessException;
import java.util.List;
import java.util.Map;

/**
 * 数据源配置管理端口（T36，方案 §4.4.2 datasource-configs 组，REQ 故事 3）。
 *
 * <p>端口在应用层、实现在基础设施层（{@code infrastructure.aggregation.DataSourceConfigFacadeImpl}）：实现需要
 * ConfigCenter 快照 / RuntimeConfigService 写路径 / data_source_event 仓储 / RoutingSourceAdapter
 * 连通性试拉等基础设施件， 应用层直接依赖会与既有 infrastructure→application 依赖成环（LayeredArchitectureTest 层切片无环约束），
 * 走端口倒置（同 {@code LlmConfigFacade} 先例）。
 *
 * <p>语义：读返回 7 源卡片（含健康徽章数据）+ 聚合总超时条；写为「当前文档合并部分字段 → 校验 → 整体落库」 （expectedUpdatedAt 并发防呆
 * 30065），保存即热生效（数据源参数全部 LIVE，方案 §4.2）。 健康口径（方案 §4.6）：最近一条 data_source_event（含 OK 心跳）类型/时间 + 24h
 * 异常计数（type∈1~4）。
 */
public interface DataSourceConfigFacade {

    /** 全量视图（7 源卡片 + 聚合总超时条，逐字段 effectiveMode）。 */
    DataSourceConfigView view();

    /**
     * 更新单源配置（PATCH 语义：仅非 null 字段合并进当前文档，LIVE 级保存即生效）。
     *
     * @throws BusinessException 30061 源不存在；2001 校验失败；30065 并发冲突
     */
    SourceCardView update(SourceCode sourceCode, DataSourceConfigUpdate update);

    /**
     * 更新聚合编排总超时（页面「聚合总超时条」，LIVE 级每请求生效，PRD 场景 3.3）。
     *
     * @throws BusinessException 2001 校验失败；30065 并发冲突
     */
    AggregationView updateAggregation(AggregationGlobalUpdate update);

    /**
     * 分源连通性测试：单源试拉一次（绕缓存），返回 ok/耗时/条数/错误摘要。 mock 模式走本地 mock 校验并附提示（不发起真实外呼）； EVENT
     * 源读本地异动表（ADR-0013）。
     *
     * @throws BusinessException 30061 源不存在
     */
    ConnectivityResult connectivityTest(SourceCode sourceCode);

    /** GET /api/v1/datasource-configs 响应。 */
    record DataSourceConfigView(List<SourceCardView> sources, AggregationView aggregation) {}

    /** 单源卡片（配置 + 健康徽章数据 + 逐字段生效级别；fallbackChain/availableProviders 为 ADR-0033 增量）。 */
    record SourceCardView(
            String sourceCode,
            String label,
            boolean enabled,
            String mode,
            long timeoutMillis,
            int retries,
            long cacheTtlSeconds,
            long failureCacheTtlSeconds,
            List<String> fallbackChain,
            List<String> availableProviders,
            Map<String, Object> params,
            HealthView health,
            String updatedAt,
            Map<String, String> effectiveModes) {}

    /** 健康徽章数据：最近一条事件（含 OK 心跳）类型/时间 + 24h 异常计数。该源从未产生事件时两字段均为 null（前端「暂无抓取记录」空态， PRD 场景 3.5）。 */
    record HealthView(String lastEventType, String lastEventAt, long errors24h) {}

    /** 聚合总超时条视图。 */
    record AggregationView(
            long detailTimeoutMillis, String updatedAt, Map<String, String> effectiveModes) {}

    /**
     * 单源部分更新（null 字段 = 不修改；params 提供即整体替换）。expectedUpdatedAt 为 ISO-8601 文本（UTC）。
     * failureCacheTtlSeconds 为 P1-5b 增量（可空 = 不修改；存量文档缺字段时保存由 facade 补种默认）。 fallbackChain 为 ADR-0033
     * 增量（null = 不修改；空清单合法 = 仅主源；提供即写入并顺带淘汰旧 {@code params.backupSource} 键）。
     */
    record DataSourceConfigUpdate(
            Boolean enabled,
            String mode,
            Long timeoutMillis,
            Integer retries,
            Long cacheTtlSeconds,
            Long failureCacheTtlSeconds,
            List<String> fallbackChain,
            Map<String, Object> params,
            String expectedUpdatedAt) {}

    /** 聚合总超时部分更新（null 字段 = 不修改）。 */
    record AggregationGlobalUpdate(Long detailTimeoutMillis, String expectedUpdatedAt) {}

    /** 连通性测试结果（ok=false 时 itemCount 为 null，error 带原因摘要；mock 模式带 note 说明）。 */
    record ConnectivityResult(
            boolean ok,
            Long latencyMillis,
            Long itemCount,
            String mode,
            String error,
            String note) {}
}
