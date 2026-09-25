package com.info.platform.domain.feed;

/**
 * 取数上下文（M13 T101，方案 §4.3 {@code FeedFetcher.fetch(source, ctx)} 的 ctx）。
 *
 * @param cursorValue 当前增量游标值（null = 首轮/无游标）：newest-first 迭代遇 {@code <=} 已见值止
 * @param pages 取数页数（正常轮 1 页；断流补抓深翻至 maxBackfillPages=3）
 */
public record FetchContext(String cursorValue, int pages) {

    /** 正常轮（第 1 页 newest-first）。 */
    public static FetchContext firstPage(String cursorValue) {
        return new FetchContext(cursorValue, 1);
    }
}
