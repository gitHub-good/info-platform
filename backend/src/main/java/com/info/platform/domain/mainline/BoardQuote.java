package com.info.platform.domain.mainline;

/**
 * 东财板块行情单行（通道 A {@code push2 clist fs=m:90+t:2} 映射行，{@code BoardAggregator} 聚合原料；未收录板块已在客户端层
 * 过滤——industry 恒非空）。
 *
 * @param boardName 东财板块名（f14，落 dim_name）
 * @param industry 申万一级行业（{@code IndustryDirectory.swPrimaryOf} 映射输出）
 * @param pctDay 当日涨跌幅 %（f3；null = 源缺失/停牌）
 * @param upCount 上涨家数（f104）
 * @param downCount 下跌家数（f105）
 * @param mainNetFlow 主力净流入 元（f62）
 * @param totalMv 板块总市值 元（f20；缺失/≤0 行触发等权回退判定）
 */
public record BoardQuote(
        String boardName,
        String industry,
        Double pctDay,
        Integer upCount,
        Integer downCount,
        Double mainNetFlow,
        Double totalMv) {}
