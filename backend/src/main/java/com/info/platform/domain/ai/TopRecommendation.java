package com.info.platform.domain.ai;

/**
 * 每日推荐条目（值对象，对齐 Spike-2 §7.4 每日推荐输出 schema 的 {@code topRecommend[]} 元素）。
 *
 * <p>领域层纯净（纯 JDK record，无 Jackson 注解——序列化/反序列化由基础设施层 {@code BriefContentParser} 用 ObjectMapper 承担，
 * 守护领域层不引框架）。每日推荐（briefType=4）输出 Top5，每只附一句话推荐理由（非买卖建议）。
 *
 * <p>{@code rank} 为模型给出的排序名次（1=最优先）；应用层取前 5 后按 {@code rank} 升序展示。
 *
 * @param subjectCode 标的内部统一代码（如 SH600519）
 * @param subjectName 标的名称
 * @param reason 一句话推荐理由（信息面活跃度/事件重要性依据，非买卖建议）
 * @param rank 排序名次（1 起，越小越优先）
 */
public record TopRecommendation(String subjectCode, String subjectName, String reason, int rank) {

    /**
     * 紧凑构造器：归一化代码/名称（缺失→空串），名次负数归 0（防模型给出负值）。
     *
     * <p>不抛异常——解析 LLM 输出时容忍瑕疵字段，由应用层按 rank 排序与截断 Top5 兜底。
     */
    public TopRecommendation {
        subjectCode = subjectCode == null ? "" : subjectCode;
        subjectName = subjectName == null ? "" : subjectName;
        reason = reason == null ? "" : reason;
        if (rank < 0) {
            rank = 0;
        }
    }
}
