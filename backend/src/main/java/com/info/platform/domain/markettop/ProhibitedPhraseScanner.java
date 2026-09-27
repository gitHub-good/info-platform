package com.info.platform.domain.markettop;

import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 违禁短语扫描（M21 T182，方案 §4.4.4 步 4 / §4.4.6 黑名单正则——FactWhitelistValidator 词表先例）：投资指令与收益承诺类表述零容忍，
 * thesis 命中 → 整体拒（模板兜底）；条目命中 → 剔除该条目（再判下限，跌破即整体拒）。
 *
 * <p>黑名单为 domain 常量（首跑后可校准扩充，ADR-0059 裁决 3）；命中留痕供 JobRunStats 计数与 T187「违禁扫描零命中」验收。
 */
public final class ProhibitedPhraseScanner {

    /**
     * 违禁黑名单（方案 §4.4.6 清单）：投资指令（买入/卖出/清仓/满仓/梭哈/抄底/加仓/建仓）+ 收益承诺（必涨/必定上涨/保证…收益/稳赚/包赚/稳获/翻倍/十倍） +
     * 定价暗示（目标价/涨停）+ 荐股（强烈推荐）。
     */
    public static final Pattern BLACKLIST =
            Pattern.compile(
                    "必涨|必定上涨|保证.{0,4}收益|稳赚|包赚|稳获|买入|卖出|清仓|满仓|梭哈|抄底|加仓|建仓|目标价|涨停|翻倍|十倍|强烈推荐");

    private ProhibitedPhraseScanner() {}

    /**
     * 扫描（§4.4.4 步 4）。
     *
     * @param parsed 对账通过（步 3）的输出
     * @return valid=false = thesis 命中或条目剔除后跌破下限（→ 模板兜底）；cleaned 为剔除后终态
     */
    public static Result scan(Parsed parsed) {
        List<String> hits = new ArrayList<>();
        String thesisHit = firstHit(parsed.thesis());
        if (thesisHit != null) {
            hits.add(thesisHit);
            return new Result(false, parsed, hits);
        }

        List<Entry> highlights = new ArrayList<>();
        List<Entry> risks = new ArrayList<>();
        for (Entry entry : parsed.highlights()) {
            if (entryHit(entry, hits) != null) {
                continue;
            }
            highlights.add(entry);
        }
        for (Entry entry : parsed.risks()) {
            if (entryHit(entry, hits) != null) {
                continue;
            }
            risks.add(entry);
        }

        boolean valid =
                highlights.size() >= DeepDiveOutputParser.HIGHLIGHTS_MIN
                        && risks.size() >= DeepDiveOutputParser.RISKS_MIN;
        return new Result(
                valid, new Parsed(parsed.thesis(), highlights, risks, parsed.dataNotes()), hits);
    }

    /** 首个命中短语（无命中返回 null——词表面回归/留痕用）。 */
    public static String firstHit(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = BLACKLIST.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /** 条目命中即剔除并留痕（返回命中词，未命中返回 null）。 */
    private static String entryHit(Entry entry, List<String> hits) {
        String hit = firstHit(entry.text());
        if (hit != null) {
            hits.add(hit);
        }
        return hit;
    }

    /** 扫描结果（hits 供留痕与验收抽检）。 */
    public record Result(boolean valid, Parsed cleaned, List<String> hits) {}
}
