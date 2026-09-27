package com.info.platform.domain.markettop;

import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.ArrayList;
import java.util.List;

/**
 * 深析终态（M21 T182，方案 §4.4.4 步 5 产物）：五步校验链通过后的 LLM 终态或模板兜底终态——dive_detail 落库载荷的领域形态（序列化在 持久化边界完成，领域不依赖
 * JSON 库）。
 *
 * <p>genMethod 留痕：LLM（校验链全过）/ TEMPLATE（任一步失败兜底；不参与 diveScore）；「degraded」（触顶/中止跳过）无产物——该标的无 本对象，合成时按
 * factor_only。
 */
public record DeepDiveOutcome(
        GenMethod method,
        String thesis,
        List<Entry> highlights,
        List<Entry> risks,
        List<Citation> citations,
        String summary) {

    /** dive_summary 上限（§4.2 dive_summary ≤160 字）。 */
    public static final int SUMMARY_MAX_CHARS = 160;

    public DeepDiveOutcome {
        thesis = thesis == null ? "" : thesis;
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
        risks = risks == null ? List.of() : List.copyOf(risks);
        citations = citations == null ? List.of() : List.copyOf(citations);
        summary = summary == null ? "" : summary;
    }

    /** LLM 终态：citations = 各条目引用去重并集（diveScore 引用计数口径）；summary = thesis + 亮点/风险合并 ≤160 字。 */
    public static DeepDiveOutcome llm(Parsed parsed) {
        List<Citation> citations = new ArrayList<>();
        for (Entry entry : parsed.highlights()) {
            addAllDistinct(citations, entry.citations());
        }
        for (Entry entry : parsed.risks()) {
            addAllDistinct(citations, entry.citations());
        }
        String summary =
                cap(
                        parsed.thesis()
                                + " 亮点："
                                + joinTexts(parsed.highlights())
                                + " 风险："
                                + joinTexts(parsed.risks()));
        return new DeepDiveOutcome(
                GenMethod.LLM,
                parsed.thesis(),
                parsed.highlights(),
                parsed.risks(),
                citations,
                summary);
    }

    /** 模板兜底终态：纯文案（无条目/无引用——不参与 diveScore，generation=FACTOR_ONLY）。 */
    public static DeepDiveOutcome template(String templateText) {
        return new DeepDiveOutcome(
                GenMethod.TEMPLATE, templateText, List.of(), List.of(), List.of(), templateText);
    }

    private static String joinTexts(List<Entry> entries) {
        StringBuilder text = new StringBuilder();
        for (Entry entry : entries) {
            if (!text.isEmpty()) {
                text.append('；');
            }
            text.append(entry.text());
        }
        return text.toString();
    }

    private static void addAllDistinct(List<Citation> target, List<Citation> source) {
        for (Citation citation : source) {
            if (!target.contains(citation)) {
                target.add(citation);
            }
        }
    }

    private static String cap(String text) {
        return text.length() <= SUMMARY_MAX_CHARS ? text : text.substring(0, SUMMARY_MAX_CHARS);
    }

    /** 生成方法留痕（market_top_rank.dive_method：LLM / TEMPLATE；factor_only 为 NULL）。 */
    public enum GenMethod {
        LLM,
        TEMPLATE
    }
}
