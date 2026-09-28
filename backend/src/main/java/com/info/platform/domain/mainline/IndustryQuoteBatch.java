package com.info.platform.domain.mainline;

import java.util.List;

/**
 * 一次全量行情采集结果（{@code IndustryQuoteSource.fetch()} 返回，双通道共用形态，方案 §4.2.1）。
 *
 * @param source 通道来源留痕（eastmoney-push2 / tencent-rank）
 * @param quoteTime 源时间戳（无源侧时间字段，取 fetch 完成时刻 Asia/Shanghai ISO-8601 带偏移；stale 判定与三方对账锚）
 * @param boards 板块明细行（通道 A 86±行；通道 B 空）
 * @param industries 31 行业行（通道 A = BoardAggregator 聚合输出；通道 B = 源直出 TENCENT_DIRECT）
 * @param unmappedBoards 未收录东财板块跳过数（安全侧 WARN 计数，任务中心 lastRunDetail 可视）
 */
public record IndustryQuoteBatch(
        String source,
        String quoteTime,
        List<BoardQuote> boards,
        List<IndustryQuote> industries,
        int unmappedBoards) {

    public IndustryQuoteBatch {
        boards = boards == null ? List.of() : List.copyOf(boards);
        industries = industries == null ? List.of() : List.copyOf(industries);
    }
}
