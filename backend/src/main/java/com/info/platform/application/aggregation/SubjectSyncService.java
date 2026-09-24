package com.info.platform.application.aggregation;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 标的池同步引擎（T51，技术方案增补 §4.3/§4.5）。
 *
 * <p>每市场独立执行「流程 A 拉取（<b>事务外</b>）→ 流程 B diff+写库（<b>单市场单事务</b>，委托 {@link SubjectSyncWriter}）」：
 * 某市场失败（拉取异常 / total 完整性不符）→ 该市场整轮放弃（零写入，不推进缺失计数），不影响已成功市场； 股票桶存在失败时 {@link #syncAll()} 抛 {@link
 * SubjectSyncException} 携带全部计数与失败摘要——T53 由 SubjectSyncJob 落 errorMessage 留痕（§3.3 方案 A 双通道）。
 *
 * <p>桶清单：A 股 + 港股（Must）；CN_INDEX 指数桶（Should）由 T54 按 {@code subject.sync.index-enabled} 开关接入。
 */
@Service
public class SubjectSyncService {

    private static final Logger log = LoggerFactory.getLogger(SubjectSyncService.class);

    private final SubjectListSource listSource;
    private final SubjectSyncWriter writer;

    public SubjectSyncService(SubjectListSource listSource, SubjectSyncWriter writer) {
        this.listSource = listSource;
        this.writer = writer;
    }

    /**
     * 整轮同步：桶间串行、互不回滚（市场级原子，跨市场独立）。
     *
     * @return 各成功市场的计数（INFO 留痕）
     * @throws SubjectSyncException 任一股票桶失败（已成功市场数据已生效，不回滚；摘要含失败原因）
     */
    public List<MarketSyncResult> syncAll() {
        List<MarketSyncResult> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (MarketSyncSpec bucket : syncedBuckets()) {
            try {
                MarketSyncResult result = syncMarket(bucket);
                results.add(result);
                log.info("标的池同步市场成功 {}: {}", bucket, result.summary());
            } catch (Exception e) {
                // 该市场整轮放弃：零写入、不推进缺失计数；跨市场独立（已成功市场不回滚）
                failures.add(bucket + " FAILED (" + e.getMessage() + ")");
                log.warn("标的池同步市场放弃 {}: {}", bucket, e.toString());
            }
        }
        if (!failures.isEmpty()) {
            throw new SubjectSyncException(results, failures);
        }
        return List.copyOf(results);
    }

    /** 单市场同步：拉取（事务外，长 HTTP 不占事务/锁）→ 写库（单市场单事务）。 任何异常 = 该市场零写入，由调用方按市场级放弃处理。 */
    public MarketSyncResult syncMarket(MarketSyncSpec bucket) {
        long fetchStart = System.currentTimeMillis();
        List<SubjectSnapshot> external = listSource.fetchAll(bucket);
        log.debug(
                "标的池同步拉取完成 {}: 行数 {} 拉取耗时 {}ms",
                bucket,
                external.size(),
                System.currentTimeMillis() - fetchStart);
        return writer.writeBucket(bucket, external);
    }

    /**
     * 本轮同步桶清单（顺序即执行顺序：A 股 → 港股）。
     *
     * <p>T54 接入点：{@code index-enabled=true} 时追加 {@link MarketSyncSpec#CN_INDEX}（失败仅 WARN 不计整轮
     * FAILED，Should 不阻塞）。
     */
    private List<MarketSyncSpec> syncedBuckets() {
        return List.of(MarketSyncSpec.A_SHARE_STOCK, MarketSyncSpec.HK_STOCK);
    }
}
