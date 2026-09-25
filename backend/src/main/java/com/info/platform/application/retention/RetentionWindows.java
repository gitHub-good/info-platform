package com.info.platform.application.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.domain.retention.RetentionLogTable;

/**
 * 留痕保留窗口的类型化视图（T71，方案 §4.2 执行侧防御）：四表各自保留天数。
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
        int readingEventDays) {

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
                dayOf(doc, RetentionLogTable.READING_EVENT));
    }

    /** 按表取窗口（四表独立判定）。 */
    public int of(RetentionLogTable table) {
        return switch (table) {
            case JOB_EXECUTION_LOG -> jobExecutionLogDays;
            case DATA_SOURCE_EVENT -> dataSourceEventDays;
            case LLM_CALL_LOG -> llmCallLogDays;
            case READING_EVENT -> readingEventDays;
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
