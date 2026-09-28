package com.info.platform.application.mainline;

import com.info.platform.domain.mainline.IndustryQuoteBatch;

/**
 * 行业行情采集源端口（M27 T242，方案 §4.2.1——SubjectListSource/IndustryBoardSource 同款依赖倒置）：一次全量拉取行业行情。
 *
 * <p>两实现（infrastructure/aggregation）：通道 A {@code EastMoneyBoardQuoteClient}（push2 板块行情，主）/ 通道 B
 * {@code TencentBoardRankClient}（腾讯板块排行 SW31 直出，备）——编排层只依赖本端口，双通道互切零改动（ADR-0063 裁决 1）。
 */
public interface IndustryQuoteSource {

    /**
     * 一次全量采集（通道 A 单请求 86±行一页；通道 B 单请求 31 行）。
     *
     * @return 已聚合的行业行 + 板块明细行（通道 B 板块行空）
     * @throws java.lang.IllegalStateException 拉取失败/完整性校验失败（调用方按通道降级处理——轮级互切）
     */
    IndustryQuoteBatch fetch();
}
