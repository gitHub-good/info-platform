package com.info.platform.domain.ai;

import java.util.List;

/**
 * AI 简报结构化内容（值对象，对齐 Spike-2 §5.1 JSON Schema，落 {@code ai_brief.content}）。
 *
 * <p>领域层纯净（纯 JDK record，无 Jackson 注解——序列化/反序列化由基础设施层 {@code BriefContentParser} 用 ObjectMapper
 * 承担，守护领域层不引框架）。顶层字段： {@code summary} 摘要 / {@code keyEvents} 关键事件 / {@code bias} 整体倾向 / {@code
 * biasReason} 倾向理由 / {@code watchSuggestion} 关注建议（非投资建议） / {@code facts} 事实数组（幻觉校验用） / {@code
 * disclaimer} 免责声明 / {@code topRecommend} 每日推荐 Top5（仅 briefType=4 产出，T23）。
 *
 * <p>紧凑构造器归一化：缺失的 {@code keyEvents}/{@code facts}/{@code topRecommend} 数组归空（防 NPE，对齐 §5.3
 * 解析兜底——模型可能省略空数组）。 {@code disclaimer} 缺失时由应用层兜底为 {@link #DEFAULT_DISCLAIMER}（保证响应恒附免责声明，§4.1.4）。
 * {@code topRecommend} 仅每日推荐型（Spike-2 §7.4）产出；其他类型该数组恒空，不影响序列化形状（{@code BriefContentParser} 已
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 容忍多/缺字段）。
 */
public record BriefContent(
        String summary,
        List<BriefKeyEvent> keyEvents,
        String bias,
        String biasReason,
        String watchSuggestion,
        List<BriefFact> facts,
        String disclaimer,
        List<TopRecommendation> topRecommend) {

    /** 全局免责声明（§4.1.4「全部响应附 disclaimer」）。 */
    public static final String DEFAULT_DISCLAIMER = "AI 生成，非投资建议";

    public BriefContent {
        keyEvents = keyEvents == null ? List.of() : List.copyOf(keyEvents);
        facts = facts == null ? List.of() : List.copyOf(facts);
        topRecommend = topRecommend == null ? List.of() : List.copyOf(topRecommend);
    }
}
