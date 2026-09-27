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
 *
 * <p>M20 扩位（T170，技术方案-V2.2-M20 §4.1）：追加 SUBJECT_FACTOR_SNAPSHOT（180/30）与 MARKET_DAILY_SNAPSHOT
 * （365/90——v2 时序分位序列资产窗更长）两枚举项；{@code retention.global} 旧 JSON 行由解析器字段级回退补默认，无数据迁移。
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
    NEWS_ITEM("news_item", "newsItemDays", 180, 30),

    /**
     * 管道逐条状态表（M15 V23；随 news_item 同窗同清——T125 并入 {@code newsItemDays} 键：两表 created_at 同刻落库，
     * 窗口一致即近似同轮清出，孤儿行窗口差 ≤一轮，ADR-0046 裁决 1 注记）。
     */
    NEWS_ANALYSIS("news_analysis", "newsItemDays", 180, 30),

    /**
     * 推荐卡片表（M16 V26 T134；随 {@code recommendationCardDays} 同窗——缺省 180 下限 30）。清出后推荐中心早卡降级为
     * 仅卡片自身字段（event/news 大字段 join 不到时留空——既有防御语义）。
     */
    RECOMMENDATION_CARD("recommendation_card", "recommendationCardDays", 180, 30),

    /**
     * 推荐反馈流水表（M16 V26 T134；与 recommendation_card 共 {@code recommendationCardDays} 键同窗同清——两表
     * created_at 同刻落库，沿 NEWS_ANALYSIS 随 newsItemDays 先例，方案 §4.1 注记）。mute 表常驻不入枚举（ACTIVE 行是
     * 有效状态，清理会静默恢复推送）。
     */
    RECOMMENDATION_FEEDBACK("recommendation_feedback", "recommendationCardDays", 180, 30),

    /**
     * 标的因子日快照表（M20 V30 T170；快照序列是 M21 榜单变动/M22 历史统计/按当时权重复现审计的对照物——默认 180 天 下限 30，方案 §4.1 键空间扩位表）。
     */
    SUBJECT_FACTOR_SNAPSHOT("subject_factor_snapshot", "subjectFactorSnapshotDays", 180, 30),

    /** 行情估值日快照表（M20 V30 T170；v2 时序分位序列资产——默认 365 天下限 90（窗更长，ADR-0058 裁决 1 配套）。 */
    MARKET_DAILY_SNAPSHOT("market_daily_snapshot", "marketDailySnapshotDays", 365, 90),

    /**
     * Top10 榜单行表（M21 V31 T180；榜单版本是 M22 统计回算原料，180 天对齐资讯库口径——方案 §4.2 键空间扩位）。 两表各自独立键（批次行数
     * 量级低但审计窗与榜单行一致）。
     */
    MARKET_TOP_RANK("market_top_rank", "marketTopRankDays", 180, 30),

    /** Top10 榜单批次表（M21 V31 T180；与 market_top_rank 同窗惯例、独立键）。 */
    MARKET_TOP_BATCH("market_top_batch", "marketTopBatchDays", 180, 30);

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
