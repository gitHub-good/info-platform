package com.info.platform.domain.retention;

/**
 * 留痕表枚举白名单（T70，ADR-0036 §3；T113 扩 news_item）——可被清理的旁路留痕/资讯库表，兼表级常量的<b>单一事实源</b>： 物理表名 / 配置字段名 /
 * 缺省窗口 / 下限被校验器（保存侧）、解析器（执行侧回退）、删除端口、种子共用。
 *
 * <p>枚举序即留痕明细段序与清理轮遍历序（固定，SUCCESS 行 error_message 五段格式由此确定）。
 *
 * <table border="1">
 *   <caption>表级常量（技术方案 §4.1 + T113）</caption>
 *   <tr><th>枚举项</th><th>physicalName</th><th>jsonField</th><th>defaultDays</th><th>minDays</th></tr>
 *   <tr><td>JOB_EXECUTION_LOG</td><td>job_execution_log</td><td>jobExecutionLogDays</td><td>30</td><td>7</td></tr>
 *   <tr><td>DATA_SOURCE_EVENT</td><td>data_source_event</td><td>dataSourceEventDays</td><td>14</td><td>2</td></tr>
 *   <tr><td>LLM_CALL_LOG</td><td>llm_call_log</td><td>llmCallLogDays</td><td>90</td><td>35</td></tr>
 *   <tr><td>READING_EVENT</td><td>reading_event</td><td>readingEventDays</td><td>90</td><td>35</td></tr>
 *   <tr><td>NEWS_ITEM</td><td>news_item</td><td>newsItemDays</td><td>180</td><td>30</td></tr>
 * </table>
 *
 * <p>NEWS_ITEM 注记（T113，REQ-20260925-11 条目 7）：判定列 created_at（V22 惯例整秒 ISO-8601 文本，与亚秒边界的
 * 比较下界兼容）；created_at 无独立索引——180d 窗口峰值 ≈36 万行（方案 §5 容量预估）全扫毫秒级可接受，量级抬升再评估 （记 M17 跟进，不为本批加索引）。
 */
public enum RetentionLogTable {

    /** Job 执行留痕表（判定列 created_at，idx_job_log_name_time 在列）。 */
    JOB_EXECUTION_LOG("job_execution_log", "jobExecutionLogDays", 30, 7),

    /** 数据源缺失/降级事件表。 */
    DATA_SOURCE_EVENT("data_source_event", "dataSourceEventDays", 14, 2),

    /** LLM 调用留痕表（判定列 created_at，idx_llm_call_log_created 在列）。 */
    LLM_CALL_LOG("llm_call_log", "llmCallLogDays", 90, 35),

    /** 阅读行为留痕表。 */
    READING_EVENT("reading_event", "readingEventDays", 90, 35),

    /** 资讯统一库（M13 V22；批次源放量写入的存储护栏，默认 180 天下限 30，T113）。 */
    NEWS_ITEM("news_item", "newsItemDays", 180, 30);

    private final String physicalName;
    private final String jsonField;
    private final int defaultDays;
    private final int minDays;

    RetentionLogTable(String physicalName, String jsonField, int defaultDays, int minDays) {
        this.physicalName = physicalName;
        this.jsonField = jsonField;
        this.defaultDays = defaultDays;
        this.minDays = minDays;
    }

    /** 物理表名（编译期常量，仅删除端口/测试可达，不外露给接口层）。 */
    public String physicalName() {
        return physicalName;
    }

    /** {@code retention.global} 文档中的字段名。 */
    public String jsonField() {
        return jsonField;
    }

    /** 缺省保留天数（键缺失/字段非法时执行侧回退值，ADR-0032 代码内置默认同系列）。 */
    public int defaultDays() {
        return defaultDays;
    }

    /** 下限天数（保存侧校验与执行侧采信的共同门槛；无上限——改大=少删，方向安全）。 */
    public int minDays() {
        return minDays;
    }
}
