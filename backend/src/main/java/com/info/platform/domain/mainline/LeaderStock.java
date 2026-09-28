package com.info.platform.domain.mainline;

/**
 * 领涨股（{@code industry_market_snapshot.leader_stock} JSON 的类型化形态，通道 B {@code lzg} 直给；通道 A 无此字段为
 * null）。
 *
 * @param code 证券代码（源前缀小写形态 {@code sh601579}，落库去前缀存 6 位）
 * @param name 股名
 * @param pct 当日涨跌幅 %
 */
public record LeaderStock(String code, String name, Double pct) {}
