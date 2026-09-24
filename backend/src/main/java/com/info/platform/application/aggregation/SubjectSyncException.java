package com.info.platform.application.aggregation;

import java.util.List;

/**
 * 标的池同步部分失败汇总异常（T51，技术方案增补 §4.5）。
 *
 * <p>股票桶任一失败时由 {@code SubjectSyncService#syncAll} 抛出：已成功市场的数据<b>不回滚</b>（跨市场独立），
 * 本异常携带全部桶的计数与失败摘要——T53 的 {@code SubjectSyncJob.run()} 直抛即由 JobExecutor 落
 * job_execution_log.error_message（§3.3 方案 A 留痕双通道）。
 */
public class SubjectSyncException extends RuntimeException {

    private final List<MarketSyncResult> results;

    private final List<String> failures;

    public SubjectSyncException(List<MarketSyncResult> results, List<String> failures) {
        super("标的池同步部分失败: " + String.join("; ", failures));
        this.results = List.copyOf(results);
        this.failures = List.copyOf(failures);
    }

    /** 已成功市场的计数（失败市场不在其中，见 {@link #getFailures()}）。 */
    public List<MarketSyncResult> getResults() {
        return results;
    }

    /** 失败市场摘要（形如 {@code HK_STOCK FAILED (原因)}）。 */
    public List<String> getFailures() {
        return failures;
    }
}
