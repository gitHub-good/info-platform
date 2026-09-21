package com.info.platform.domain.ai;

/**
 * AI 简报事实条目（值对象，对齐 Spike-2 §5.1 {@code facts[]} Schema）。
 *
 * <p>领域层纯净（纯 JDK record）。每条事实承载幻觉校验所需信息： {@code metric} 与 T04 财务 adapter 字段键对齐（如 {@code roe}/{@code
 * gross_margin}/{@code net_profit}），供 {@code HallucinationChecker} 回查真值； {@code value} 为模型给出的数值；
 * {@code source}/{@code sourceUrl} 供事实回链（回链率 100% 验收）。 {@code claim}
 * 为人类可读的数值陈述（如「归母净利润同比增长15%」），定性陈述不参与数值校验。
 *
 * <p>{@code value} 可空——模型对定性事实（如事件影响）可能不带数值，此类 fact 不参与幻觉校验（Spike-2 §6「校验范围仅限带数值的财务事实」）。
 *
 * @param claim 人类可读的事实陈述（如「归母净利润同比增长15%」）
 * @param metric 指标键（如 {@code roe}/{@code gross_margin}/{@code net_profit}），与数据源字段键对齐
 * @param value 数值（可空，定性事实不带数值则不校验）
 * @param source 数据源标识（如 {@code FINANCE}/{@code VALUATION}），回查 SourceAdapter 依据
 * @param sourceUrl 原文链接（事实回链）
 */
public record BriefFact(
        String claim, String metric, Double value, String source, String sourceUrl) {}
