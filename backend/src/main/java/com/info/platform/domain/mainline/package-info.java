/**
 * mainline 域（M27 T242~T244，技术方案-M27 §2/§4.7）：行业行情快照 → 主线榜单 → 龙头识别的领域纯函数与端口。
 *
 * <p>分层：本包只有纯函数计算器（{@code BoardAggregator}/{@code MainlineCalculator}/{@code LeaderCalculator} ——零
 * LLM 零 IO，同输入重算零漂移）与仓储端口（JDBC 实现在 infrastructure/mainline）；双通道行情客户端端口在 application/mainline（{@code
 * IndustryQuoteSource}，实现在 infrastructure/aggregation）。
 */
package com.info.platform.domain.mainline;
