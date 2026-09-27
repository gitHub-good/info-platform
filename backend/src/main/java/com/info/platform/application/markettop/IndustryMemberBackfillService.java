package com.info.platform.application.markettop;

import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectListSource;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.application.markettop.IndustryBoardSource.IndustryBoardRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 行业成员双通道回填编排（M21 T180，方案 §4.1.2 + ADR-0059 裁决 1②）：MARKET_TOP_JOB 阶段 0 的回联保障（Job 挂接留
 * T183；本类为服务层入口，手动/测试直调同路径）。
 *
 * <p>执行序：<b>通道 A datacenter</b>（确定性兜底——{@code RPT_WEB_RESPREDICT.INDUSTRY_BOARD} 2933 只 / 30
 * 页实测稳定）先行， 覆盖率仍低于阈值再试<b>通道 B push2 clist</b>（机会主义——f100 间歇封禁 ADR-0030，任一异常 WARN 跳过不阻塞）。两通道均只补
 * {@code industry IS NULL} 行（幂等可重跑，二轮零写入）；整段失败降级继续（缺成员照跑，路 C 覆盖率如实反映在 NO_ASSOC 计数）。
 *
 * <p>写入的是东财板块<b>原文</b>（subject_master.industry 变为「东财口径、单调稳定」，§3.1）；SW 映射在快照读侧
 * IndustryDirectory.swPrimaryOf 完成——回填不预映射，避免双处映射漂移。
 */
@Service
public class IndustryMemberBackfillService {

    private static final Logger log = LoggerFactory.getLogger(IndustryMemberBackfillService.class);

    private final IndustryMemberStore store;
    private final IndustryBoardSource datacenterBoards;
    private final SubjectListSource clistSource;
    private final MarketTopConfigSettings configSettings;

    @Autowired
    public IndustryMemberBackfillService(
            IndustryMemberStore store,
            IndustryBoardSource datacenterBoards,
            @Qualifier("eastMoneyListClient") SubjectListSource clistSource,
            MarketTopConfigSettings configSettings) {
        this.store = store;
        this.datacenterBoards = datacenterBoards;
        this.clistSource = clistSource;
        this.configSettings = configSettings;
    }

    /**
     * 覆盖率预检 + 双通道回填（阶段 0 入口）。
     *
     * @return 回填报告（覆盖率前后 + 各通道补填计数；预检达标时 attempted=false 零外呼）
     */
    public BackfillReport backfillIfBelowFloor() {
        long active = store.countActiveAShares();
        long withIndustry = store.countActiveASharesWithIndustry();
        double floor = configSettings.current().memberCoverageFloor();
        if (active <= 0 || coverage(withIndustry, active) >= floor) {
            log.info(
                    "行业成员覆盖率预检达标，跳过回填: withIndustry={} active={} coverage={} floor={}",
                    withIndustry,
                    active,
                    coverage(withIndustry, active),
                    floor);
            return new BackfillReport(active, withIndustry, withIndustry, false, 0, 0);
        }

        // 通道 A：datacenter 确定性兜底（整段失败 WARN 降级继续——缺成员照跑）
        int datacenterUpdated = 0;
        try {
            datacenterUpdated = backfillViaDatacenter();
        } catch (Exception e) {
            log.warn("datacenter 行业回填通道失败（降级继续，缺成员照跑）: {}", String.valueOf(e));
        }

        // 通道 B：push2 clist 机会主义（仅当覆盖率仍低于阈值；任一异常跳过不阻塞）
        int clistUpdated = 0;
        long afterDatacenter = store.countActiveASharesWithIndustry();
        if (coverage(afterDatacenter, active) < floor) {
            try {
                clistUpdated = backfillViaClist();
            } catch (Exception e) {
                log.warn("clist 行业回填机会通道跳过（间歇封禁不阻塞，ADR-0030）: {}", String.valueOf(e));
            }
        }

        long after = store.countActiveASharesWithIndustry();
        BackfillReport report =
                new BackfillReport(
                        active, withIndustry, after, true, clistUpdated, datacenterUpdated);
        log.info(
                "行业成员回填完成: before={} after={} active={} coverage={}→{} datacenter={} clist={}",
                withIndustry,
                after,
                active,
                coverage(withIndustry, active),
                coverage(after, active),
                datacenterUpdated,
                clistUpdated);
        return report;
    }

    /** 通道 A：全量板块行 → 6 位代码补市场前缀 → 只补 NULL 行。 */
    private int backfillViaDatacenter() {
        List<IndustryBoardRow> rows = datacenterBoards.fetchIndustryBoards();
        int updated = 0;
        for (IndustryBoardRow row : rows) {
            if (row.industryBoard() == null || row.industryBoard().isBlank()) {
                continue;
            }
            updated +=
                    store.backfillIndustryIfAbsent(
                            prefixedCodeOf(row.securityCode()), row.industryBoard());
        }
        return updated;
    }

    /** 通道 B：clist 全量快照行（f100 已归一 null）→ 只补 NULL 行（全量覆盖面，机会主义）。 */
    private int backfillViaClist() {
        int updated = 0;
        for (SubjectSnapshot snapshot : clistSource.fetchAll(MarketSyncSpec.A_SHARE_STOCK)) {
            if (snapshot.industry() == null || snapshot.industry().isBlank()) {
                continue;
            }
            updated += store.backfillIndustryIfAbsent(snapshot.subjectCode(), snapshot.industry());
        }
        return updated;
    }

    /**
     * 6 位证券代码 → 带市场前缀的 subject_code（subject_master 口径）：6→SH / 0|3→SZ / 其余→BJ（北交所不在 A 股同步桶，前缀
     * 尝试不命中即零行，无害）。
     */
    static String prefixedCodeOf(String securityCode) {
        if (securityCode == null || securityCode.isBlank()) {
            return securityCode;
        }
        char first = securityCode.charAt(0);
        String prefix = first == '6' ? "SH" : (first == '0' || first == '3') ? "SZ" : "BJ";
        return prefix + securityCode;
    }

    private static double coverage(long withIndustry, long active) {
        return active <= 0 ? 0.0 : (double) withIndustry / active;
    }

    /**
     * 回填报告（JobRunStats detail 留痕 + T183 阶段 0 detail 接线；attempted=false 即预检达标零外呼）。
     *
     * @param activeSubjects 分母（A 股启用标的数）
     * @param withIndustryBefore / withIndustryAfter 回填前后分子
     * @param attempted 是否执行了回填（false = 预检达标跳过）
     * @param clistUpdated / datacenterUpdated 各通道实际补填行数（幂等口径：二轮重跑应为 0）
     */
    public record BackfillReport(
            long activeSubjects,
            long withIndustryBefore,
            long withIndustryAfter,
            boolean attempted,
            int clistUpdated,
            int datacenterUpdated) {

        /** 留痕明细段串（「members=…;coverage=…→…;dc=…;clist=…」）。 */
        public String detail() {
            return "members="
                    + withIndustryAfter
                    + "/"
                    + activeSubjects
                    + ";coverage="
                    + String.format(java.util.Locale.ROOT, "%.3f", coverageRate())
                    + ";dc="
                    + datacenterUpdated
                    + ";clist="
                    + clistUpdated;
        }

        /** 覆盖率（after 口径；分母 0 记 0）。 */
        public double coverageRate() {
            return activeSubjects <= 0 ? 0.0 : (double) withIndustryAfter / activeSubjects;
        }
    }
}
