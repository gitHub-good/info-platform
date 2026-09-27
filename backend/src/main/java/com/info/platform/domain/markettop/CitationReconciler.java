package com.info.platform.domain.markettop;

import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 引用与库对账（M21 T182，方案 §4.4.5 + ADR-0059 裁决 3「零幻觉断言」的机制化防线）：深析输出 citations 与输入白名单做 <b>结构化 (type,id)
 * 集合比对</b>（invalid = citations − whitelist）——无效引用剔除（WARN 留痕计数）、剔除后 0 有效引用的条目整体剔除、
 * 条目数跌破下限即整体拒（调用方切模板兜底不重试）。
 *
 * <p>领域纯函数（零依赖，可独立复算——T187 验收对全部深析输出断言 citations ⊆ whitelist 的单一事实源）。
 */
public final class CitationReconciler {

    private CitationReconciler() {}

    /**
     * 对账（§4.4.4 步 3）。
     *
     * @param parsed 结构校验通过（步 2）的输出
     * @param whitelist 输入白名单（DeepDiveInput.citationWhitelist 单源派生）
     * @return valid=false = 条目数跌破下限（整体拒 → 模板兜底）；reconciled 为剔除后终态（valid=true 时才被消费）
     */
    public static Result reconcile(Parsed parsed, Set<Citation> whitelist) {
        Set<Citation> allowed = whitelist == null ? Set.of() : whitelist;
        List<Entry> highlights = new ArrayList<>();
        List<Entry> risks = new ArrayList<>();
        int invalidCitations = 0;
        int droppedEntries = 0;

        for (Entry entry : parsed.highlights()) {
            EntryOutcome outcome = reconcileEntry(entry, allowed);
            invalidCitations += outcome.invalidCitations();
            if (outcome.entry() == null) {
                droppedEntries++;
            } else {
                highlights.add(outcome.entry());
            }
        }
        for (Entry entry : parsed.risks()) {
            EntryOutcome outcome = reconcileEntry(entry, allowed);
            invalidCitations += outcome.invalidCitations();
            if (outcome.entry() == null) {
                droppedEntries++;
            } else {
                risks.add(outcome.entry());
            }
        }

        boolean valid =
                highlights.size() >= DeepDiveOutputParser.HIGHLIGHTS_MIN
                        && risks.size() >= DeepDiveOutputParser.RISKS_MIN;
        return new Result(
                valid,
                new Parsed(parsed.thesis(), highlights, risks, parsed.dataNotes()),
                invalidCitations,
                droppedEntries);
    }

    /** 单条目对账：无效引用剔除 → 0 有效引用条目剔除；合法引用去重保序。 */
    private static EntryOutcome reconcileEntry(Entry entry, Set<Citation> allowed) {
        Set<Citation> valid = new LinkedHashSet<>();
        int invalid = 0;
        for (Citation citation : entry.citations()) {
            if (citation != null && citation.knownType() && allowed.contains(citation)) {
                valid.add(citation);
            } else {
                invalid++;
            }
        }
        if (valid.isEmpty()) {
            return new EntryOutcome(null, invalid);
        }
        return new EntryOutcome(new Entry(entry.text(), List.copyOf(valid)), invalid);
    }

    /** 条目对账结果（entry=null 即剔除）。 */
    private record EntryOutcome(Entry entry, int invalidCitations) {}

    /** 对账结果（invalidCitations/droppedEntries 供 JobRunStats detail.citationDrops 留痕）。 */
    public record Result(
            boolean valid, Parsed reconciled, int invalidCitations, int droppedEntries) {}
}
