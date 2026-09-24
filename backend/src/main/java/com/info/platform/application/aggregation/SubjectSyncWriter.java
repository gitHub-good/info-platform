package com.info.platform.application.aggregation;

import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标的池同步的单市场写库单元（T51，技术方案增补 §4.3 流程 B——diff + 写库，单市场单事务）。
 *
 * <p>从 {@code SubjectSyncService} 拆出的原因（ADR-0029）：拉取必须在事务外（长 HTTP 不占事务/锁），写库必须单市场单事务—— 两个边界在同一类里靠
 * 自调用无法穿过 Spring 代理，独立 bean 让 {@code @Transactional} 落在真实调用链上。
 *
 * <p>diff 四分支（§4.3）：新增 {@code INSERT OR IGNORE} 分批（默认批 500 防 SQLite 长事务）； 出现行仅名称/行业/取数键变化时
 * UPDATE（<b>不碰 status / missing_streak</b>，REQ 红线）；出现且 streak&gt;0 → 回归清零； 缺失启用标的
 * missing_streak+1（已停用不再计数）。 <b>本批停用动作不接</b>（T52：达阈值 {@code status=0} 单向守卫，挂点见 {@link
 * #collectMissing} 注释）。
 */
@Service
public class SubjectSyncWriter {

    private static final Logger log = LoggerFactory.getLogger(SubjectSyncWriter.class);

    private final SubjectRepository repository;
    private final int batchSize;

    public SubjectSyncWriter(
            SubjectRepository repository, @Value("${subject.sync.batch-size:500}") int batchSize) {
        this.repository = repository;
        this.batchSize = batchSize <= 0 ? 500 : batchSize;
    }

    /** 单市场 diff + 写库（事务内）；耗时为写库阶段口径（拉取在事务外，由服务层另行留痕）。 */
    @Transactional
    public MarketSyncResult writeBucket(MarketSyncSpec bucket, List<SubjectSnapshot> external) {
        long startMillis = System.currentTimeMillis();
        Map<String, Subject> dbByCode = loadBaseline(bucket);
        Set<String> externalCodes = new LinkedHashSet<>();
        List<Subject> toInsert = new ArrayList<>();
        int updated = 0;
        int unchanged = 0;
        for (SubjectSnapshot snapshot : external) {
            externalCodes.add(snapshot.subjectCode());
            Subject existing = dbByCode.get(snapshot.subjectCode());
            if (existing == null) {
                toInsert.add(newSubject(snapshot, bucket));
                continue;
            }
            if (refreshExisting(existing, snapshot)) {
                updated++;
            } else {
                unchanged++;
            }
        }
        int inserted = insertInBatches(bucket, toInsert);
        int missing = collectMissing(bucket, dbByCode, externalCodes);
        log.info(
                "标的池同步写库完成 {}: 新增 {} 更新 {} 不变 {} 缺失确认 {}（停用动作 T52 接入）",
                bucket,
                inserted,
                updated,
                unchanged,
                missing);
        return new MarketSyncResult(
                bucket,
                inserted,
                updated,
                unchanged,
                missing,
                0,
                external.size(),
                System.currentTimeMillis() - startMillis);
    }

    /** diff 基线：该市场+类型现存全量行（不筛状态，§4.4 SQL ⑤）。 */
    private Map<String, Subject> loadBaseline(MarketSyncSpec bucket) {
        Map<String, Subject> byCode = new LinkedHashMap<>();
        for (Subject row : repository.loadBucket(bucket.market(), bucket.subjectType())) {
            byCode.put(row.getSubjectCode().value(), row);
        }
        return byCode;
    }

    /**
     * 出现行保鲜：名称/行业/取数键任一变化才 UPDATE（既有额外取数键增量合入不丢失，ADR-0029）； streak&gt;0 时回归清零（与字段更新解耦）。
     *
     * @return 是否产生了字段更新（仅 streak 清零不计入 updated）
     */
    private boolean refreshExisting(Subject existing, SubjectSnapshot snapshot) {
        String code = snapshot.subjectCode();
        boolean nameChanged = !snapshot.name().equals(existing.getName());
        boolean industryChanged = !Objects.equals(snapshot.industry(), existing.getIndustry());
        Map<String, String> mergedCodes = mergeExternalCodes(existing, snapshot);
        boolean codesChanged = !mergedCodes.equals(existing.getExternalCodes());
        if (existing.getMissingStreak() > 0) {
            repository.clearMissingStreak(code);
        }
        if (nameChanged || industryChanged || codesChanged) {
            repository.updateSnapshot(code, snapshot.name(), snapshot.industry(), mergedCodes);
            return true;
        }
        return false;
    }

    /** 既有取数键 + 快照生成键的增量合并（生成键覆盖同名键，手工额外键如 akshare 保留，ADR-0029）。 */
    private static Map<String, String> mergeExternalCodes(
            Subject existing, SubjectSnapshot snapshot) {
        Map<String, String> merged =
                existing.getExternalCodes() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(existing.getExternalCodes());
        merged.putAll(snapshot.externalCodes());
        return merged;
    }

    private Subject newSubject(SubjectSnapshot snapshot, MarketSyncSpec bucket) {
        return Subject.builder()
                .subjectCode(SubjectCode.of(snapshot.subjectCode()))
                .market(bucket.market())
                .subjectType(bucket.subjectType())
                .name(snapshot.name())
                .industry(snapshot.industry())
                .externalCodes(snapshot.externalCodes())
                .build();
    }

    /** 新增分批 INSERT OR IGNORE（批 500 防长事务；幂等——冲突行被 IGNORE 不计入）。 */
    private int insertInBatches(MarketSyncSpec bucket, List<Subject> toInsert) {
        int inserted = 0;
        for (int from = 0; from < toInsert.size(); from += batchSize) {
            List<Subject> batch =
                    toInsert.subList(from, Math.min(from + batchSize, toInsert.size()));
            inserted += repository.insertIgnoreBatch(batch);
        }
        if (toInsert.size() > batchSize) {
            log.debug("标的池同步新增分批 bucket={} 总数 {} 批大小 {}", bucket, toInsert.size(), batchSize);
        }
        return inserted;
    }

    /**
     * 缺失确认：启用标的不在本轮全量结果 → missing_streak+1（已停用不计数）。
     *
     * <p><b>T52 挂点</b>：此处按「旧 streak + 1 ≥ 阈值」补调 {@code repository.deactivateIfMissingReached(code,
     * threshold)}（端口与 SQL 守卫 {@code WHERE status=1 AND missing_streak >= ?} 由 T52 落）， 停用数计入返回值
     * MarketSyncResult.deactivated——本批只计数上报不停用。
     */
    private int collectMissing(
            MarketSyncSpec bucket, Map<String, Subject> dbByCode, Set<String> externalCodes) {
        int missing = 0;
        for (Subject row : dbByCode.values()) {
            if (row.getStatus() == SubjectStatus.ENABLED
                    && !externalCodes.contains(row.getSubjectCode().value())) {
                repository.incrementMissingStreak(row.getSubjectCode().value());
                missing++;
            }
        }
        return missing;
    }
}
