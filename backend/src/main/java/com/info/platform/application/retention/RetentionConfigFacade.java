package com.info.platform.application.retention;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 留痕窗口读写端口（T72，方案 §4.3）：GET/PATCH {@code /api/v1/retention/windows} 的应用层门面。
 *
 * <p>端口在应用层、实现在基础设施层（{@code infrastructure.retention.RetentionConfigFacadeImpl}）：实现需读写
 * runtime_config（RuntimeConfigService 换快照与事件发布），与既有 LlmConfigFacade / JobCenterFacade 同因（应用层直依
 * 基础设施件会成环）。读侧走 {@link RetentionWindows}（字段级回退防御）；写侧拼全量文档委托 {@code RuntimeConfigService.write}（校验
 * 2001 + expectedUpdatedAt 30065 + 换快照热生效）。
 */
public interface RetentionConfigFacade {

    /** 当前窗口视图（GET /api/v1/retention/windows：窗口 + 各表下限/默认 + updatedAt）。 */
    WindowsView view();

    /**
     * 全量替换窗口（PATCH 语义：四字段 write 语义整体替换）。
     *
     * @throws com.info.platform.domain.common.BusinessException 2001 字段缺失/非法（校验器字段级）；30065 并发冲突；
     *     50000 写库失败（快照不动，旧值继续生效）
     */
    WindowsView update(WindowsUpdate update);

    /** GET 响应：窗口 + 各字段下限/默认（Dialog 预填与校验提示数据源）+ updatedAt（下次防呆比对）。 */
    record WindowsView(Windows windows, Map<String, FieldLimits> limits, String updatedAt) {}

    /** 四窗口值（键名与 retention.global 文档字段一致）。 */
    record Windows(
            int jobExecutionLogDays,
            int dataSourceEventDays,
            int llmCallLogDays,
            int readingEventDays) {}

    /**
     * 单字段护栏：下限与默认（取枚举常量）。
     *
     * <p>{@code default} 为契约键名（§4.3 GET 响应），Java 记录组件名避开保留字用 {@code defaultValue}。
     */
    record FieldLimits(int min, @JsonProperty("default") int defaultValue) {}

    /** PATCH 请求体：四字段全量（null/缺失 → 校验器 2001 必填拦截，不部分写）；expectedUpdatedAt 可选并发防呆。 */
    record WindowsUpdate(
            Integer jobExecutionLogDays,
            Integer dataSourceEventDays,
            Integer llmCallLogDays,
            Integer readingEventDays,
            String expectedUpdatedAt) {}
}
