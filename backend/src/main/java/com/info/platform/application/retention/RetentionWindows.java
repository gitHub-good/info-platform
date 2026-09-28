package com.info.platform.application.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.domain.retention.RetentionLogTable;

/**
 * 留痕保留窗口的类型化视图（T71，方案 §4.2 执行侧防御；T113 扩 newsItemDays、T134 扩 recommendationCardDays、 T170 扩 M20
 * 两快照表、T183 扩榜单两表、M27 T242 扩行业行情快照/主线两表三键）：十四字段覆盖十五表（news_analysis 随 newsItemDays、
 * recommendation_feedback 随 recommendationCardDays 共窗；其余各表独立窗）。
 *
 * <p><b>每轮现读</b>：{@link RetentionCleanupService} 在每轮 run() 内读取当前快照解析（用时读取即热生效，改窗口下一轮按新界）。
 * 解析规则（字段级回退，{@link #resolve}）：字段存在且为整型且 ≥ 表下限 → 采信；否则回退该表 {@code defaultDays}
 * ——一个字段被绕过校验写坏，其余好字段不受牵连；「DB 无配置 = 用默认，不是全删」，{@code 设 0 清空} 不可达。
 *
 * <p>写路径合法性由 {@code RetentionConfigValidator} 把关（保存侧第一道防御），本类是第二道（执行侧）。
 */
public record RetentionWindows(
        int jobExecutionLogDays,
        int dataSourceEventDays,
        int llmCallLogDays,
        int readingEventDays,
        int newsItemDays,
        int recommendationCardDays,
        int subjectFactorSnapshotDays,
        int marketDailySnapshotDays,
        int marketTopRankDays,
        int marketTopBatchDays,
        int incrementalReevalLogDays,
        int industryMarketSnapshotDays,
        int industryMainlineDays,
        int industryMainlineBatchDays) {

    /** 全默认窗口（键缺失/文档损坏时的兜底，值取枚举 defaultDays 单一事实源）。 */
    public static RetentionWindows defaults() {
        return resolve(null);
    }

    /**
     * 从 {@code retention.global} JSON 文档解析（快照内共享节点只读）。
     *
     * @param doc 配置文档；null / 非对象 → 全默认
     */
    public static RetentionWindows resolve(JsonNode doc) {
        return new RetentionWindows(
                dayOf(doc, RetentionLogTable.JOB_EXECUTION_LOG),
                dayOf(doc, RetentionLogTable.DATA_SOURCE_EVENT),
                dayOf(doc, RetentionLogTable.LLM_CALL_LOG),
                dayOf(doc, RetentionLogTable.READING_EVENT),
                dayOf(doc, RetentionLogTable.NEWS_ITEM),
                dayOf(doc, RetentionLogTable.RECOMMENDATION_CARD),
                dayOf(doc, RetentionLogTable.SUBJECT_FACTOR_SNAPSHOT),
                dayOf(doc, RetentionLogTable.MARKET_DAILY_SNAPSHOT),
                dayOf(doc, RetentionLogTable.MARKET_TOP_RANK),
                dayOf(doc, RetentionLogTable.MARKET_TOP_BATCH),
                dayOf(doc, RetentionLogTable.INCREMENTAL_REEVAL_LOG),
                dayOf(doc, RetentionLogTable.INDUSTRY_MARKET_SNAPSHOT),
                dayOf(doc, RetentionLogTable.INDUSTRY_MAINLINE),
                dayOf(doc, RetentionLogTable.INDUSTRY_MAINLINE_BATCH));
    }

    /**
     * 按表取窗口（各表独立判定；共窗：NEWS_ANALYSIS←newsItemDays[T125]，RECOMMENDATION_FEEDBACK←recommendationCardDays[T134]）。
     */
    public int of(RetentionLogTable table) {
        return switch (table) {
            case JOB_EXECUTION_LOG -> jobExecutionLogDays;
            case DATA_SOURCE_EVENT -> dataSourceEventDays;
            case LLM_CALL_LOG -> llmCallLogDays;
            case READING_EVENT -> readingEventDays;
            case NEWS_ITEM, NEWS_ANALYSIS -> newsItemDays;
            case RECOMMENDATION_CARD, RECOMMENDATION_FEEDBACK -> recommendationCardDays;
            case SUBJECT_FACTOR_SNAPSHOT -> subjectFactorSnapshotDays;
            case MARKET_DAILY_SNAPSHOT -> marketDailySnapshotDays;
            case MARKET_TOP_RANK -> marketTopRankDays;
            case MARKET_TOP_BATCH -> marketTopBatchDays;
            case INCREMENTAL_REEVAL_LOG -> incrementalReevalLogDays;
            case INDUSTRY_MARKET_SNAPSHOT -> industryMarketSnapshotDays;
            case INDUSTRY_MAINLINE -> industryMainlineDays;
            case INDUSTRY_MAINLINE_BATCH -> industryMainlineBatchDays;
        };
    }

    /** 单字段解析：存在且为整型且 ≥ 下限 → 采信；否则回退该表默认（字段级独立，互不牵连）。 */
    private static int dayOf(JsonNode doc, RetentionLogTable table) {
        if (doc == null || !doc.isObject()) {
            return table.defaultDays();
        }
        JsonNode field = doc.get(table.jsonField());
        if (field == null || !field.isIntegralNumber()) {
            return table.defaultDays();
        }
        int value = field.asInt();
        return value >= table.minDays() ? value : table.defaultDays();
    }
}
