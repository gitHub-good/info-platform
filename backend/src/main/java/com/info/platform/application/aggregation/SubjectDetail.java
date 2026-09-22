package com.info.platform.application.aggregation;

import java.util.List;
import java.util.Map;

/**
 * 标的详情聚合 DTO（应用层返回值，由 {@link AggregationService} 组装）。
 *
 * <p>对齐技术方案 §4.1.1 响应结构：subject 基本信息 + 各分区数据（quote/finance/valuation 为对象、
 * announcements/news/policies 为数组）+ sourceStatus（每分区 ok/missing/failed/timeout）。 events 分区（本地
 * anomaly_event 近期异动/事件，ADR-0013）为 T08 实现偏差新增，同为数组承载。
 *
 * <p>单源缺失/异常时对应分区为 {@code null}，sourceStatus 标注原因，前端按 sourceStatus 兜底，不阻断其他分区。
 */
public record SubjectDetail(
        SubjectInfo subject,
        Map<String, Object> quote,
        Map<String, Object> finance,
        Map<String, Object> valuation,
        List<Map<String, Object>> announcements,
        List<Map<String, Object>> news,
        List<Map<String, Object>> policies,
        List<Map<String, Object>> events,
        Map<String, String> sourceStatus) {

    /** 标的基本信息（subject_master 投影，不含外部代码等内部映射字段）。 */
    public record SubjectInfo(
            String subjectCode, String name, String market, int type, String industry) {}
}
