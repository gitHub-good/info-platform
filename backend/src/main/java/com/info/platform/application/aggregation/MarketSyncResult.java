package com.info.platform.application.aggregation;

/**
 * 单市场桶同步计数产出（T51，技术方案增补 §4.3/§4.8）。
 *
 * <p>成功轮进应用 INFO 日志（{@link #summary()}）；部分失败轮由 {@code SubjectSyncService} 抛携带各桶计数的汇总异常 →
 * JobExecutor 落 errorMessage（§3.3 双通道方案 A，T53 接线）。
 *
 * @param bucket 市场桶
 * @param inserted 新增行数（INSERT OR IGNORE 实际插入，幂等重跑为 0）
 * @param updated 更新行数（名称/行业/取数键任一变化；不碰 status / missing_streak）
 * @param unchanged 不变行数（出现且无任何变化，零写入）
 * @param missing 缺失确认数（启用标的不在本轮全量结果 → missing_streak+1；本批只计数上报，停用动作 T52）
 * @param deactivated 停用数（缺失达阈值被停用；本批恒 0，T52 接阈值停用后生效）
 * @param total 本轮源全量行数（与源 total 校验一致）
 * @param elapsedMillis 该桶写库阶段耗时（拉取在事务外，由服务层另行留痕）
 */
public record MarketSyncResult(
        MarketSyncSpec bucket,
        int inserted,
        int updated,
        int unchanged,
        int missing,
        int deactivated,
        int total,
        long elapsedMillis) {

    /** 单行摘要（INFO 日志 / errorMessage 统一口径，对齐 §4.5 样例格式）。 */
    public String summary() {
        return bucket
                + " (inserted="
                + inserted
                + ",updated="
                + updated
                + ",unchanged="
                + unchanged
                + ",missing="
                + missing
                + ",deactivated="
                + deactivated
                + ",total="
                + total
                + ",elapsed="
                + elapsedMillis
                + "ms)";
    }
}
