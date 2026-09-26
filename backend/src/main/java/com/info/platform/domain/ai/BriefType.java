package com.info.platform.domain.ai;

/**
 * 简报类型（{@code prompt_template.brief_type} 持久化为 {@code TINYINT}，亦作 {@link LlmRequest#briefTypeKey()}
 * 的缓存分区/成本归因键）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TINYINT} 互转。1~4 对齐 Spike-2 §7 四类 v1 模板： 1 个股 / 2 事件归因 / 3 政策解读 / 4
 * 每日推荐；M15（ADR-0046 裁决 3）扩管道场景 5 行业归类（V23 播种 v1.0，治理页零改动自动可见）/ 6 事件提取（V24 播种 v1.0，T122）/ 7
 * 行业日报（T124 播种）；M16 T132（ADR-0051 裁决 3）扩 8 推荐卡片逻辑链（V27 播种 v1.0）。
 *
 * <p>{@link #key()} 返回字符串码（如 {@code "1"}），供 T21 构造 {@link LlmRequest#json(java.util.List, String)
 * LlmRequest.json(messages, briefTypeKey)} 时直接传入（{@code briefTypeKey} 即此 key，见 LlmRequest 契约）。
 */
public enum BriefType {
    /** 个股简报（行情/财务/估值/公告/新闻聚合，briefType=1）。 */
    STOCK(1, "个股简报"),
    /** 事件归因简报（单一事件对标的的影响方向与力度，briefType=2）。 */
    EVENT_ATTRIBUTION(2, "事件归因"),
    /** 政策解读（宏观政策倾向利好/利空/中性，briefType=3，关联 T28）。 */
    POLICY(3, "政策解读"),
    /** 每日推荐（自选池信息面活跃度排序 Top5，briefType=4，关联 T23）。 */
    DAILY_RECOMMEND(4, "每日推荐"),
    /** 行业归类（L1 批量归类管道模板，briefType=5，M15 / ADR-0046 裁决 3；scene 留痕与护栏成本口径键）。 */
    L1_CLASSIFY(5, "行业归类"),
    /** 事件提取（L2 批量结构化事件管道模板，briefType=6，M15 T122 / ADR-0046 裁决 3；V24 播种 v1.0）。 */
    L2_EXTRACT(6, "事件提取"),
    /** 行业日报（L3 日报叙述模板，briefType=7，M15 T124 / ADR-0046 裁决 3；V25 播种 v1.0——数字全部统计注入，AI 只写叙述）。 */
    INDUSTRY_DAILY(7, "行业日报"),
    /** 推荐卡片逻辑链（M16 T132 / ADR-0051 裁决 3；V27 播种 v1.0——LLM 仅语言组织零新增事实，输出经 FactWhitelistValidator 四类白名单校验拒即模板兜底）。 */
    RECOMMEND_CARD(8, "推荐卡片");

    private final int code;
    private final String displayName;

    BriefType(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 持久化码（对齐 DDL {@code brief_type TINYINT}）。 */
    public int code() {
        return code;
    }

    /**
     * 字符串码，作 {@link LlmRequest} 的 {@code briefTypeKey}（缓存分区 + 成本归因键，见 Spike-2 §11.1）。
     *
     * @return 如 {@code "1"} / {@code "4"}
     */
    public String key() {
        return String.valueOf(code);
    }

    /** 中文展示名（M5 模板管理与占位符注册表接口的分组标题用，T45/T46）。 */
    public String displayName() {
        return displayName;
    }

    /** 从持久化码反查枚举（未知码抛 {@code IllegalArgumentException}）。 */
    public static BriefType fromCode(int code) {
        for (BriefType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知 briefType: " + code);
    }
}
