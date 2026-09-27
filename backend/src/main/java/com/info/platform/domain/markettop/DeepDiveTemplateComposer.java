package com.info.platform.domain.markettop;

import java.util.Locale;

/**
 * 深析模板兜底文案（M21 T182，方案 §4.4.6）：确定性中性短文——数字全部来自结构化输入（ADR-0054 周报「AI 只写叙述」的更保守档： 模板连叙述都不用
 * AI）。dive_method=TEMPLATE、generation=FACTOR_ONLY（不参与 diveScore）。
 */
public final class DeepDiveTemplateComposer {

    /** 固定免责尾句（卡片级免责三处必载之一）。 */
    static final String DISCLAIMER_TAIL = "以上为数据整理，不构成投资建议。";

    private DeepDiveTemplateComposer() {}

    /**
     * 组装兜底文案（§4.4.6 模板原文）：{@code 近{N}日 {N1} 条关联事件（最高重要度 {imp}，最新 {date}）；所属行业 {ind} 24h 热度排名第
     * {R}；五维评分 {F1}/{F2}/{F3}/{F4}（权重 {w}）。…不构成投资建议。}
     *
     * <p>缺项如实省略/标注（无事件 → 「近 N 日无关联事件」；行业未归属 → 「所属行业未归属」；热度未上榜 → 省略排名段）——不编造任何输入外事实。
     */
    public static String compose(TemplateFacts facts) {
        StringBuilder text = new StringBuilder();
        if (facts.eventCount() > 0) {
            text.append("近")
                    .append(facts.eventWindowDays())
                    .append("日 ")
                    .append(facts.eventCount())
                    .append(" 条关联事件（最高重要度 ")
                    .append(facts.maxImportance() == null ? "未知" : facts.maxImportance())
                    .append("，最新 ")
                    .append(facts.latestEventDate() == null ? "无日期" : facts.latestEventDate())
                    .append("）；");
        } else {
            text.append("近").append(facts.eventWindowDays()).append("日无关联事件；");
        }
        if (facts.industry() == null || facts.industry().isBlank()) {
            text.append("所属行业未归属；");
        } else {
            text.append("所属行业 ").append(facts.industry());
            if (facts.industryHeatRank() > 0) {
                text.append(" 24h 热度排名第 ").append(facts.industryHeatRank());
            }
            text.append("；");
        }
        text.append("五维评分 ")
                .append(score(facts.fCatalyst()))
                .append('/')
                .append(score(facts.fConduction()))
                .append('/')
                .append(score(facts.fFundamental()))
                .append('/')
                .append(score(facts.fRisk()))
                .append("（权重 ")
                .append(weight(facts.wCatalyst()))
                .append('|')
                .append(weight(facts.wConduction()))
                .append('|')
                .append(weight(facts.wFundamental()))
                .append('|')
                .append(weight(facts.wRisk()))
                .append("）。")
                .append(DISCLAIMER_TAIL);
        return text.toString();
    }

    private static String score(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String weight(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * 模板事实（全部结构化输入派生，缺项以 null/0 如实表达）。
     *
     * @param eventWindowDays 事件窗天数
     * @param eventCount 依据事件总数（三维护据合计）
     * @param maxImportance 最高事件重要度（HIGH/MEDIUM/LOW；无事件 null）
     * @param latestEventDate 最新依据事件日（yyyy-MM-dd；无事件 null）
     * @param industry 申万一级行业（未归属 null）
     * @param industryHeatRank 行业 24h 热度排名（1 起；未上榜 0）
     * @param fCatalyst/fConduction/fFundamental/fRisk 四维评分（估值维不进模板——权重 0.00）
     * @param wCatalyst/wConduction/wFundamental/wRisk 对应权重
     */
    public record TemplateFacts(
            int eventWindowDays,
            int eventCount,
            String maxImportance,
            String latestEventDate,
            String industry,
            int industryHeatRank,
            double fCatalyst,
            double fConduction,
            double fFundamental,
            double fRisk,
            double wCatalyst,
            double wConduction,
            double wFundamental,
            double wRisk) {}
}
