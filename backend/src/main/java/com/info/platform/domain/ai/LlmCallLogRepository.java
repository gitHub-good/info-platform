package com.info.platform.domain.ai;

import java.time.Instant;
import java.util.List;

/**
 * LLM 调用留痕仓储端口（依赖倒置：领域层定义、基础设施层实现，T30）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。写入方为基础设施层 {@code LlmCallLogger}（封装
 * {@code LlmGatewayImpl} 的留痕落库，失败仅记日志不阻断调用）； 查询方为应用层 {@code LlmCostReportService}
 * （成本报表窗口聚合，经接口层 {@code LlmCostReportController} 暴露）。
 */
public interface LlmCallLogRepository {

    /**
     * 落库（追加型流水，仅 INSERT）：回填 id/createdAt 并返回落库后的实体。
     *
     * @param log 已定型（markCacheHit/markSuccess/markFailed/markRejected 之一）的留痕
     * @return 含回填 id/createdAt 的记录
     */
    LlmCallLog save(LlmCallLog log);

    /**
     * 查询 {@code created_at >= start} 的留痕（newest-first，id DESC）。
     *
     * <p>成本报表窗口取数：{@code start} 为窗口起点（今日零点 / 近 N 天），个人量级窗口行数有限， {@code limit}
     * 仅作上限护栏（超出按最新 limit 条聚合，成本口径截断在报表页脚注说明）。
     *
     * @param start 窗口起点（含）
     * @param limit 返回条数上限
     */
    List<LlmCallLog> findCreatedSince(Instant start, int limit);
}
