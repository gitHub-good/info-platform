package com.info.platform.domain.push;

import java.util.Optional;

/**
 * 推送类型（push_record.push_type 持久化为 TINYINT，SSE 事件名为 eventName）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TINYINT} 互转、与 SSE {@code event:} 字段名互转。 对齐技术方案 §4.1.3 SSE 事件类型
 * anomaly/event/policy/ai_brief/daily_recommend 与 §4.2 push_record DDL 注释。 首期（T14）实现 {@link
 * #ANOMALY} 异动推送； M14 T115 扩 {@link #SOURCE_ALERT}/{@link #SOURCE_RECOVERED} 源异常事件（码 6/7， V6 DDL 的
 * TINYINT 无 CHECK 约束、仅注释——扩码无需迁移）； 其余类型留枚举占位，后续任务（T15 事件 / T24 政策 / T21 AI 简报 / T23 每日推荐）补消费链路。
 */
public enum PushType {
    /** 异动推送（AnomalyDetectedEvent 触发，refId=anomaly_event.id）。 */
    ANOMALY(1, "anomaly"),
    /** 事件推送（重大公告/事件入库触发，预留）。 */
    EVENT(2, "event"),
    /** 政策推送（政策时事流，预留）。 */
    POLICY(3, "policy"),
    /** AI 简报推送（AiBriefDoneEvent 触发，预留）。 */
    AI_BRIEF(4, "ai_brief"),
    /** 每日推荐推送（盘前 Top5，预留）。 */
    DAILY_RECOMMEND(5, "daily_recommend"),
    /** 源异常告警（M14 T115：连续失败达阈值或退避 ≥30 分钟，SourceAlertEvent 触发，全量用户广播）。 */
    SOURCE_ALERT(6, "source_alert"),
    /** 源恢复通知（M14 T115 Should：告警态源恢复成功抓取时一条，feed.alert.recovered-enabled 可关）。 */
    SOURCE_RECOVERED(7, "source_recovered"),
    /** 管道成本熔断告警（M15 T125：scene 5/6/7 当日成本 ≥90% 日预算，PipelineGuardService 发布，全量用户广播）。 */
    PIPELINE_FUSED(8, "pipeline_fused"),
    /** 行业日报生成完成提醒（M15 T124 Should：INDUSTRY_DAILY_REPORT 成功落库后发布，全量用户广播）。 */
    INDUSTRY_REPORT(9, "industry_report");

    private final int code;
    private final String eventName;

    PushType(int code, String eventName) {
        this.code = code;
        this.eventName = eventName;
    }

    /** 持久化 TINYINT 码。 */
    public int code() {
        return code;
    }

    /** SSE {@code event:} 字段名（小写下划线，对齐 §4.1.3）。 */
    public String eventName() {
        return eventName;
    }

    /** 持久化码 → 枚举。 */
    public static PushType fromCode(int code) {
        for (PushType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知 pushType: " + code);
    }

    /** SSE 事件名 → 枚举（未知返回 empty，供接口层 history 的 type 参数校验）。 */
    public static Optional<PushType> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (PushType t : values()) {
            if (t.eventName.equalsIgnoreCase(name.trim())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
