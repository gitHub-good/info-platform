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
 * missing_streak+1（已停用不再计数），<b>旧 streak+1 达阈值（默认 3 可配）→ 停用 status=0 单向守卫</b>（T52，{@code
 * SubjectRepository#deactivateIfMissingReached}）。
 */
@Service
public class SubjectSyncWriter {

    private static final Logger log = LoggerFactory.getLogger(SubjectSyncWriter.class);

    /** 停用阈值的代码缺省（§4.2 参数表：PRD 默认 3；yml 缺失/非法回落此值）。 */
    private static final int DEFAULT_STREAK_THRESHOLD = 3;

    private final SubjectRepository repository;
    private final int batchSize;
    private final int missingStreakThreshold;

    public SubjectSyncWriter(
            SubjectRepository repository,
            @Value("${subject.sync.batch-size:500}") int batchSize,
            @Value("${subject.sync.missing-streak-threshold:3}") int missingStreakThreshold) {
        this.repository = repository;
        this.batchSize = batchSize <= 0 ? 500 : batchSize;
        this.missingStreakThreshold =
                missingStreakThreshold <= 0 ? DEFAULT_STREAK_THRESHOLD : missingStreakThreshold;
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
        MissingOutcome outcome = collectMissing(bucket, dbByCode, externalCodes);
        log.info(
                "标的池同步写库完成 {}: 新增 {} 更新 {} 不变 {} 缺失确认 {} 停用 {}（阈值 {}）",
                bucket,
                inserted,
                updated,
                unchanged,
                outcome.missing(),
                outcome.deactivated(),
                missingStreakThreshold);
        return new MarketSyncResult(
                bucket,
                inserted,
                updated,
                unchanged,
                outcome.missing(),
                outcome.deactivated(),
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
     * 缺失确认（T52）：启用标的不在本轮全量结果 → missing_streak+1（已停用不计数）； 本轮计数后达阈值（旧 streak + 1 ≥ threshold，基线读数即
     * increment 前值）→ {@link SubjectRepository#deactivateIfMissingReached} 停用（SQL 级 status=1
     * 单向守卫），真实翻转行计入 deactivated。
     */
    private MissingOutcome collectMissing(
            MarketSyncSpec bucket, Map<String, Subject> dbByCode, Set<String> externalCodes) {
        int missing = 0;
        int deactivated = 0;
        for (Subject row : dbByCode.values()) {
            if (row.getStatus() == SubjectStatus.ENABLED
                    && !externalCodes.contains(row.getSubjectCode().value())) {
                String code = row.getSubjectCode().value();
                repository.incrementMissingStreak(code);
                missing++;
                if (row.getMissingStreak() + 1 >= missingStreakThreshold
                        && repository.deactivateIfMissingReached(code, missingStreakThreshold)
                                == 1) {
                    deactivated++;
                    log.info(
                            "标的池同步停用标的 {}（连续缺失 {} 轮达阈值，status 1→0 不复活）",
                            code,
                            row.getMissingStreak() + 1);
                }
            }
        }
        return new MissingOutcome(missing, deactivated);
    }

    /** 缺失确认轮产出：缺失计数与其中达阈值被停用的行数。 */
    private record MissingOutcome(int missing, int deactivated) {}
}
