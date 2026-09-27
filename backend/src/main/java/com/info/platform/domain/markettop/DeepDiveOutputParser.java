package com.info.platform.domain.markettop;

import java.util.List;
import java.util.Optional;

/**
 * 深析输出解析端口（M21 T182，方案 §4.4.4 校验链步 1/2）：LLM 原文 → {@link Parsed} 结构。
 *
 * <p>领域层纯净接口（BriefContentCodec 同款依赖倒置——解析兜底链去 fence 标记 / 取首{到末} / 容忍未知字段由基础设施实现承担， Jackson
 * 不进领域层）；结构校验 {@link #structurallyValid} 是纯函数常驻本类型（步 2）。
 */
public interface DeepDiveOutputParser {

    /** thesis 上限（§4.4.4 步 2：非空 ≤120 字）。 */
    int THESIS_MAX_CHARS = 120;

    /** highlight/risk 条目文本上限（§4.4.4 步 2：每条 ≤80 字）。 */
    int ENTRY_MAX_CHARS = 80;

    /** highlights 合格区间（恰 2~4 条）。 */
    int HIGHLIGHTS_MIN = 2;

    /** highlights 合格上限。 */
    int HIGHLIGHTS_MAX = 4;

    /** risks 合格区间（恰 2~3 条）。 */
    int RISKS_MIN = 2;

    /** risks 合格上限。 */
    int RISKS_MAX = 3;

    /**
     * 步 1：解析 LLM 原文（实现自担 fence/解释文字容错）。
     *
     * @param rawContent LLM 返回原文（可空）
     * @return 解析成功结构；空/非法 → empty（调用方切模板兜底，不重试）
     */
    Optional<Parsed> parse(String rawContent);

    /**
     * 步 2：结构校验（thesis 非空 ≤120 字；highlights 恰 2~4、risks 恰 2~3；条目文本非空 ≤80 字；列表非 null）。 引用存在性归步 3
     * 对账——此处不校验 citations。
     */
    static boolean structurallyValid(Parsed parsed) {
        if (parsed == null
                || parsed.thesis() == null
                || parsed.thesis().isBlank()
                || parsed.thesis().length() > THESIS_MAX_CHARS
                || parsed.highlights() == null
                || parsed.risks() == null) {
            return false;
        }
        if (parsed.highlights().size() < HIGHLIGHTS_MIN
                || parsed.highlights().size() > HIGHLIGHTS_MAX
                || parsed.risks().size() < RISKS_MIN
                || parsed.risks().size() > RISKS_MAX) {
            return false;
        }
        for (Entry entry : parsed.highlights()) {
            if (!entryValid(entry)) {
                return false;
            }
        }
        for (Entry entry : parsed.risks()) {
            if (!entryValid(entry)) {
                return false;
            }
        }
        return true;
    }

    /** 单条目判（文本非空 ≤80 字；citations 可空——步 3 对账处置）。 */
    static boolean entryValid(Entry entry) {
        return entry != null
                && entry.text() != null
                && !entry.text().isBlank()
                && entry.text().length() <= ENTRY_MAX_CHARS;
    }

    /** 深析输出结构（校验链的流转载体；citations 为原始输出，无效引用由对账剔除）。 */
    record Parsed(
            String thesis, List<Entry> highlights, List<Entry> risks, List<String> dataNotes) {

        public Parsed {
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
            risks = risks == null ? List.of() : List.copyOf(risks);
            dataNotes = dataNotes == null ? List.of() : List.copyOf(dataNotes);
        }
    }

    /** 亮点/风险条目（text + 引用清单）。 */
    record Entry(String text, List<Citation> citations) {

        public Entry {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }
}
