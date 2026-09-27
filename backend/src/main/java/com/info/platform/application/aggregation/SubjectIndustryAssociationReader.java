package com.info.platform.application.aggregation;

import com.info.platform.application.valuation.FactorSnapshotService;
import com.info.platform.application.valuation.ValuationSettings;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.IndustryAssociator;
import com.info.platform.domain.valuation.IndustryAssociator.Association;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 单标的行业关联集即时现算读口（V2.3-M23 T202，ADR-0062 裁决三 a 案）：复用 {@link FactorSnapshotService} 同款三路窗口投影（SQL
 * 侧值位引号定界 LIKE 裁剪到单标的）喂<b>同一纯函数</b> {@link IndustryAssociator#associate} ——与 F2/M22
 * 增量受影响集构造性同源（同一关联逻辑，抽验标的可复算：本读口输出 vs 全量投影逐集相等，Gate 2 断言）。
 *
 * <p>窗口与锚点与 F2/M22 完全同参：W2 = {@code ValuationSettings.params().assocWindowDays()}（缺省 30，热调与全量快照同源）、
 * snapshotDate = 当日（Asia/Shanghai）、路 C 成员边 weight 0.3 只补空。新鲜度锚定当下（非 17:30 快照）； 零新表零新 Job
 * 零迁移。否决快照落表与直读 factor_detail（ADR-0062 裁决三候选对比）。
 */
@Component
public class SubjectIndustryAssociationReader {

    /** 快照口径时区（与 FactorSnapshotService.SNAPSHOT_ZONE 同口径）。 */
    private static final ZoneId SNAPSHOT_ZONE = ZoneId.of("Asia/Shanghai");

    private final FactorSnapshotRepository repository;
    private final ValuationSettings settings;

    public SubjectIndustryAssociationReader(
            FactorSnapshotRepository repository, ValuationSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /**
     * 单标的关联集（按权重降序、最近优先、行业名升序——IndustryAssociator 确定性序）。
     *
     * <p>三查询毫秒级（W2 窗内数千行级 LIKE 扫，SQLite 索引面）——每次详情页查看现算，方案 §6 性能口径。
     *
     * @param subjectCode 标的代码（subject_master.subject_code 口径，如 SH600519）
     */
    public List<Association> associationsOf(String subjectCode) {
        int windowDays = settings.params().assocWindowDays();
        LocalDate snapshotDate = LocalDate.now(SNAPSHOT_ZONE);
        Map<String, List<Association>> bySubject =
                IndustryAssociator.associate(
                        FactorSnapshotService.eventLinks(
                                repository.findEventsInWindowForSubject(
                                        subjectCode,
                                        snapshotDate.minusDays(windowDays - 1L).toString(),
                                        snapshotDate.toString())),
                        FactorSnapshotService.newsLinks(
                                repository.findMatchedNewsInWindowForSubject(
                                        subjectCode,
                                        windowStartIso(snapshotDate, windowDays),
                                        dayEndIso(snapshotDate))),
                        FactorSnapshotService.memberLinks(
                                repository.findIndustryMemberOf(subjectCode)),
                        snapshotDate,
                        windowDays);
        return bySubject.getOrDefault(subjectCode, List.of());
    }

    /** W2 窗下界（ISO，上海日界 00:00 含）——与 FactorSnapshotService 同公式（同源复算用例锁定漂移）。 */
    private static String windowStartIso(LocalDate snapshotDate, int windowDays) {
        return snapshotDate
                .minusDays(windowDays - 1L)
                .atStartOfDay(SNAPSHOT_ZONE)
                .toInstant()
                .toString();
    }

    /** 快照日日界上界（ISO，次日 00:00 不含）——与 FactorSnapshotService 同公式。 */
    private static String dayEndIso(LocalDate snapshotDate) {
        return snapshotDate.plusDays(1).atStartOfDay(SNAPSHOT_ZONE).toInstant().toString();
    }
}
