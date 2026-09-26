package com.info.platform.domain.recommendation;

import com.info.platform.domain.analysis.Direction;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 逻辑链模板拼接（领域纯函数，M16 T132，方案 §4.5 步骤 6 / REQ 拍板二三形态原文）：LLM 失败/被拒/降级态的兜底文案， ≤1s 纯拼接零 LLM。三环节结构 =
 * 事件环节（summary 前置）—— 逻辑环节（关联判定「该事件直接涉及/因该事件影响行业」）—— 用户环节（「你关注的标的/行业/订阅」）。
 *
 * <p>P1：「{summary}——该事件直接涉及你关注的标的{标的1、标的2}。」
 *
 * <p>P2（有标的区）：「{summary}——因该事件{方向词}{行业}行业，你关注的标的属于该行业。」
 *
 * <p>P2（空标的区，通道 B 空转形态）：「{summary}——你关注的{行业}行业受该事件{方向词}影响（源于你订阅的{主题}）。」
 * （无命中主题时省略来源注；行业缺省用「该事件影响你关注的行业」措辞，不硬凑）
 *
 * <p>P3：「{summary}——与你订阅的{主题/事件类型}相关。」
 */
public final class LogicChainTemplates {

    private LogicChainTemplates() {}

    /** 拼接输入（summary + 方向 + 关联结果要素；eventTypeLabel 供 P3 无主题时的订阅来源文案）。 */
    public record TemplateInput(
            RecLevel level,
            String summary,
            Direction direction,
            List<String> industries,
            List<RecommendationCard.CardSubject> subjects,
            String matchedTheme,
            String eventTypeLabel) {}

    /** 按主关联层级拼接（未知层级按 P3 兜底，不抛）。 */
    public static String render(TemplateInput input) {
        String summary = input.summary() == null ? "" : input.summary().trim();
        return switch (input.level()) {
            case P1 -> p1(summary, input.subjects());
            case P2 -> p2(summary, input);
            case P3 -> p3(summary, input.matchedTheme(), input.eventTypeLabel());
        };
    }

    private static String p1(String summary, List<RecommendationCard.CardSubject> subjects) {
        String names = joinNames(subjects);
        if (names.isEmpty()) {
            return summary + "——该事件直接涉及你关注的标的。"; // 防御：P1 判定保证 ≥1，理论不可达
        }
        return summary + "——该事件直接涉及你关注的标的" + names + "。";
    }

    private static String p2(String summary, TemplateInput input) {
        String industry =
                input.industries() == null || input.industries().isEmpty()
                        ? null
                        : input.industries().get(0);
        String themeNote =
                input.matchedTheme() == null || input.matchedTheme().isBlank()
                        ? ""
                        : "（源于你订阅的" + input.matchedTheme() + "）";
        if (hasSubjects(input)) {
            if (industry == null) {
                return summary + "——该事件影响你关注的行业" + themeNote + "。";
            }
            return summary
                    + "——因该事件"
                    + industryWord(input.direction())
                    + industry
                    + "行业，你关注的标的属于该行业。";
        }
        if (industry == null) {
            return summary + "——该事件影响你关注的行业" + themeNote + "。";
        }
        return summary
                + "——你关注的"
                + industry
                + "行业受该事件"
                + directionWord(input.direction())
                + "影响"
                + themeNote
                + "。";
    }

    private static String p3(String summary, String matchedTheme, String eventTypeLabel) {
        if (matchedTheme != null && !matchedTheme.isBlank()) {
            return summary + "——与你订阅的" + matchedTheme + "相关。";
        }
        String source =
                eventTypeLabel == null || eventTypeLabel.isBlank()
                        ? "事件类型"
                        : eventTypeLabel + "类事件";
        return summary + "——与你订阅的" + source + "相关。";
    }

    private static boolean hasSubjects(TemplateInput input) {
        return input.subjects() != null && !input.subjects().isEmpty();
    }

    private static String joinNames(List<RecommendationCard.CardSubject> subjects) {
        if (subjects == null) {
            return "";
        }
        return subjects.stream()
                .map(RecommendationCard.CardSubject::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining("、"));
    }

    /** P2 有标的形态的方向词（NEUTRAL 用「影响」避免「中性电子行业」病句）。 */
    private static String industryWord(Direction direction) {
        return direction == Direction.NEUTRAL ? "影响" : direction.displayName();
    }

    /** P2 空标的形态的方向词（NEUTRAL 用「中性」——「受该事件中性影响」）。 */
    private static String directionWord(Direction direction) {
        return direction.displayName();
    }
}
