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
 *
 * <p>M12（REQ-20260925-09 / ADR-0037 决策 3）：追加<b>可选附加键</b> {@code sectionPagination}——首屏分页条所需总数搭
 * 聚合取数便车透出（不发第二次请求），仅对应分区 sourceStatus=ok 时填充（null 分量 = 该分区不产出）， 新闻/政策无总数语义永不出现。
 * 既有键全部不动（非破坏，前端类型加可选字段即可）。
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
        Map<String, String> sourceStatus,
        SectionPagination sectionPagination) {

    /**
     * 既有 9 参构造（M12 前形态）：sectionPagination 缺省 null——存量消费方/测试零改动； 聚合服务组装时显式传附加键。
     */
    public SubjectDetail(
            SubjectInfo subject,
            Map<String, Object> quote,
            Map<String, Object> finance,
            Map<String, Object> valuation,
            List<Map<String, Object>> announcements,
            List<Map<String, Object>> news,
            List<Map<String, Object>> policies,
            List<Map<String, Object>> events,
            Map<String, String> sourceStatus) {
        this(subject, quote, finance, valuation, announcements, news, policies, events,
                sourceStatus, null);
    }

    /** 标的基本信息（subject_master 投影，不含外部代码等内部映射字段）。 */
    public record SubjectInfo(
            String subjectCode, String name, String market, int type, String industry) {}

    /**
     * 首屏分页元数据附加键（方案 §4.1.4）：announce/event 两分量，仅对应分区 ok 时非 null； 全 null 时整体为 null
     * （无分区可分页——前端不渲染分页控件，与三态契约互斥）。
     */
    public record SectionPagination(AnnouncePageMeta announce, EventPageMeta event) {

        /** 公告分区首屏总数（东财 total_hits，随 SourceCache 快照 5min 内一致；巨潮生效不产出）。 */
        public record AnnouncePageMeta(Long total, boolean paginationSupported, String moreUrl) {}

        /** 事件分区 7 天窗内精确总数（本地 count，毫秒级）。 */
        public record EventPageMeta(long total) {}
    }
}
