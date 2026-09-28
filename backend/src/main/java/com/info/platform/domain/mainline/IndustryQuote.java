package com.info.platform.domain.mainline;

/**
 * 申万行业聚合行情单行（{@code industry_market_snapshot} INDUSTRY 行的类型化形态）。
 *
 * @param industry 申万一级行业名（dim_name 同值）
 * @param pctDay 当日涨跌幅 %（聚合口径见 aggMethod）
 * @param pctD5 5 日累计涨跌幅 %（通道 B 源直给；通道 A 由服务层按本表历史自算——本记录恒 null，自算在落库前完成）
 * @param upCount 上涨家数（Σ f104 / zgb 前数——加法直聚）
 * @param downCount 下跌家数（Σ f105 / zgb 后数）
 * @param mainNetFlow 主力净流入 元（Σ f62 / zljlr×10⁴——加法直聚）
 * @param totalMv 总市值 元（Σ f20 / zsz×10⁴——加法直聚）
 * @param leaderStock 领涨股（通道 B；通道 A null）
 * @param aggMethod CAP_WEIGHTED / EQUAL / TENCENT_DIRECT（聚合方式留痕，页面脚注可查）
 */
public record IndustryQuote(
        String industry,
        Double pctDay,
        Double pctD5,
        Integer upCount,
        Integer downCount,
        Double mainNetFlow,
        Double totalMv,
        LeaderStock leaderStock,
        String aggMethod) {}
