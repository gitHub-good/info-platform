package com.info.platform.domain.analysis;

/**
 * 结构化事件类型（{@code event_item.event_type} 白名单，M15 T122，方案 §4.1/REQ 拍板「L3 事件类型首批 ≥8 类」——9 值含 OTHER
 * 兜底）。
 *
 * <p>适用于全部容器（宏观/监管容器的高价值条目同样提取为事件，如降准）；模型输出越界值按拆批规则处理进失败统计（模板漂移可见不静默，ADR-0046 裁决 3 同款语义）。
 */
public enum EventType {
    /** 业绩预告。 */
    EARNINGS_FORECAST("业绩预告"),
    /** 并购重组。 */
    MA_MERGER("并购重组"),
    /** 回购·增持·减持。 */
    BUYBACK_CHANGE("回购·增持·减持"),
    /** 重大合同·中标。 */
    MAJOR_CONTRACT("重大合同·中标"),
    /** 政策发布。 */
    POLICY_RELEASE("政策发布"),
    /** 监管处罚·立案。 */
    REGULATORY_PENALTY("监管处罚·立案"),
    /** 高管变动。 */
    EXEC_CHANGE("高管变动"),
    /** 技术突破·产品发布。 */
    TECH_BREAKTHROUGH("技术突破·产品发布"),
    /** 其他（兜底类）。 */
    OTHER("其他");

    private final String displayName;

    EventType(String displayName) {
        this.displayName = displayName;
    }

    /** 中文展示名（事件流卡片徽章用）。 */
    public String displayName() {
        return displayName;
    }

    /** 从持久化/模型文本反查（未知值返回 null——调用方按非法枚举行处理，不抛异常打断批解析）。 */
    public static EventType fromName(String name) {
        if (name == null) {
            return null;
        }
        for (EventType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
