package com.info.platform.application.jobrun;

/**
 * 轮次统计上报（可选接口，T71 / ADR-0036 §2）——计数型 Job 向执行通道上报本轮处理条数与留痕明细。
 *
 * <p>{@code JobExecutor} 在 {@code run()} 正常返回后按 {@code instanceof} 读取，写入 SUCCESS 留痕
 * （processed_count = 处理条数；error_message = 留痕明细，「终态附加信息」语义）。不实现本接口的 Job 行为字节级不变 （SUCCESS 恒 (0,0) 且明细
 * null）；FAILED 路径不读本接口。
 *
 * <p>并发安全：JobExecutor 每 jobKey CAS 守卫保证同 Job 串行，实现用普通字段即可（轮首重置）。
 */
public interface JobRunStats {

    /** 本轮处理条数（如留痕清理轮的四表删除合计）。 */
    int lastProcessedCount();

    /** 本轮留痕明细（null = 无明细，error_message 保持 NULL）；计数型 Job 建议遵循「表名=行数」段式约定。 */
    String lastRunDetail();
}
