package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.NewsAnalysisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * OBS-04 留痕行一次性归位（M16 T130，方案 §4.2）：定位 {@code news_analysis.l2_status='EXTRACTED'} 但 {@code
 * event_item} 无对应行的孤儿留痕行（M15 BUG-01 处置残留，实测 1 行），回置 {@code FAILED}（attempts 留痕不动）。
 *
 * <p>归位后自愈路径：attempts 未满上限的 FAILED 行由既有 L2 重扫窗口（当日 FAILED[attempts 未满]）自然补提取，重新产出
 * event_item——不做常驻对账 Job（event_item {@code UNIQUE(news_id)} + UPSERT 既有语义已防新增孤儿）。
 *
 * <p>使用方式：手动一次性触发（测试与运维工具语义）；dev/prod 库等价 SQL 留档于任务提交说明—— {@code UPDATE news_analysis SET
 * l2_status='FAILED', updated_at=... WHERE l2_status='EXTRACTED' AND news_id NOT IN (SELECT news_id
 * FROM event_item)}。
 */
@Service
public class L2TraceRepair {

    private static final Logger log = LoggerFactory.getLogger(L2TraceRepair.class);

    private final NewsAnalysisRepository repository;

    public L2TraceRepair(NewsAnalysisRepository repository) {
        this.repository = repository;
    }

    /**
     * 执行一轮归位（幂等：无孤儿返回 0；已归位行不再匹配条件）。
     *
     * @return 归位行数
     */
    public int repairOrphanExtractedRows() {
        int repaired = repository.failOrphanExtractedRows();
        if (repaired > 0) {
            log.warn("OBS-04 归位完成：{} 行孤儿 EXTRACTED 留痕行回置 FAILED（attempts 留痕不动）", repaired);
        } else {
            log.info("OBS-04 归位检查：无孤儿 EXTRACTED 留痕行");
        }
        return repaired;
    }
}
