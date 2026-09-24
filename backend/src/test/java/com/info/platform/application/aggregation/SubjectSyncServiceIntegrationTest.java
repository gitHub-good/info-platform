package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * SubjectSyncService 引擎集成测试（T51/T52）：真实 SQLite 共享内存库（V18 列已建）+ mock 列表源（不真实外呼 push2）。 手工装配
 * batch-size=2 的 writer 验证批量边界；覆盖 §6 引擎 diff 要点—— 首轮建池（种子行不变 + 新增插入）/ 更新不碰 status（停用标的照常更新名称行业）/
 * 幂等二轮差异为零 / 缺失计数只推进启用行 / 回归清零 / 市场失败不推进计数且跨市场独立（A 股生效、港股放弃）/ T52 缺失达阈值停用（status
 * 1→0、阈值未达不停、市场失败不假停用、 停用后回归不复活）。
 *
 * <p>共享库隔离（T52 起 streak 具备停用副作用，防测试间按执行顺序互相污染）：{@link #resetStockObservationState()} 在每个用例后
 * 把两个股票桶全部行 的 missing_streak 归零、status 复位 1——任何用例开始时观察态恒为「全 0 全启用」，断言与类执行顺序解耦。
 */
@SpringBootTest
@ActiveProfiles("test")
class SubjectSyncServiceIntegrationTest {

    /** 手工装配的写库批量（防长事务分批的边界用 2 触发：5 只新标的 → 3 批）。 */
    private static final int TEST_BATCH_SIZE = 2;

    /** 手工装配的停用阈值（§4.2 参数表 PRD 默认 3）。 */
    private static final int TEST_STREAK_THRESHOLD = 3;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetStockObservationState() {
        // 股票桶观察态复位（含本类与同库其他测试类留下的 streak/停用痕迹）；指数桶不经同步测试触碰，无需复位
        jdbcTemplate.update(
                "UPDATE subject_master SET missing_streak = 0, status = 1 "
                        + "WHERE subject_type = 1 AND market IN ('A_SHARE', 'HK')");
    }

    // ---- 首轮：种子对齐 + 新增建池 + 批量分批 ----

    @Test
    void syncAll_firstRound_seedsAligned_newInsertedInBatches_updateApplied() {
        SubjectListSource source = mock(SubjectListSource.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(
                        List.of(
                                // V2 种子完全一致 → unchanged（含 external_codes 双键等值）
                                snapshot("600519", "贵州茅台", "白酒"),
                                // V17 种子改名 → updated（行业/取数键不变）
                                snapshot("600036", "招商银行CMB", "银行"),
                                // 5 只新上市 → inserted（分 3 批：2/2/1）
                                snapshot("688001", "华兴源创", "半导体"),
                                snapshot("688002", "睿创微纳", "半导体"),
                                snapshot("688003", "澜起科技", "半导体"),
                                snapshot("688004", "心脉医疗", "医疗器械"),
                                snapshot("688005", "容百科技", "新材料")));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));
        SubjectSyncService service = newService(source);

        List<MarketSyncResult> results = service.syncAll();

        MarketSyncResult aShare = results.get(0);
        assertThat(aShare.bucket()).isEqualTo(MarketSyncSpec.A_SHARE_STOCK);
        assertThat(aShare.inserted()).isEqualTo(5);
        assertThat(aShare.updated()).isEqualTo(1);
        assertThat(aShare.unchanged()).isEqualTo(1);
        assertThat(aShare.total()).isEqualTo(7);

        // 新增行落库即带取数键（行情/估值链路零改造可取）+ status=1 + streak=0
        Subject listed = subjectRepository.findByCode(SubjectCode.of("SH688001")).orElseThrow();
        assertThat(listed.getMarket()).isEqualTo(Market.A_SHARE);
        assertThat(listed.getSubjectType()).isEqualTo(SubjectType.STOCK);
        assertThat(listed.getStatus()).isEqualTo(SubjectStatus.ENABLED);
        assertThat(listed.getMissingStreak()).isZero();
        assertThat(listed.getExternalCodes())
                .containsEntry("eastmoney", "1.688001")
                .containsEntry("tushare", "688001.SH");

        // 更新行：名称变化生效、取数键保留
        Subject renamed = subjectRepository.findByCode(SubjectCode.of("SH600036")).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("招商银行CMB");
        assertThat(renamed.getExternalCodes()).containsEntry("eastmoney", "1.600036");

        // 还原种子名称（共享内存库跨测试类复用，不留脏数据）
        subjectRepository.updateSnapshot(
                "SH600036", "招商银行", "银行", Map.of("eastmoney", "1.600036", "tushare", "600036.SH"));
    }

    // ---- 更新不碰 status（停用标的照常更新名称/行业，PRD 故事 2 场景 4） ----

    @Test
    void syncAll_updateKeepsDisabledStatus_andDoesNotCountMissing() {
        Subject disabled =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SH601997"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("停用旧名")
                        .externalCodes(Map.of("akshare", "sh601997"))
                        .status(SubjectStatus.DISABLED)
                        .build();
        subjectRepository.save(disabled);

        SubjectListSource source = mock(SubjectListSource.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(
                        List.of(
                                snapshot("601997", "停用新名", "新行业"),
                                snapshot("600519", "贵州茅台", "白酒")));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));
        List<MarketSyncResult> results = newService(source).syncAll();

        // 停用标的照常更新（updated 计入），但 status 不被复活、缺失也不计数
        assertThat(results.get(0).updated()).isEqualTo(1);
        Subject updated = subjectRepository.findByCode(SubjectCode.of("SH601997")).orElseThrow();
        assertThat(updated.getName()).isEqualTo("停用新名");
        assertThat(updated.getIndustry()).isEqualTo("新行业");
        assertThat(updated.getStatus()).isEqualTo(SubjectStatus.DISABLED);
        assertThat(updated.getMissingStreak()).isZero();
        assertThat(updated.getExternalCodes())
                .containsEntry("akshare", "sh601997")
                .containsEntry("eastmoney", "1.601997");
    }

    // ---- 幂等：二轮跑写入差异为零 ----

    @Test
    void syncAll_secondRound_zeroWriteDelta() {
        SubjectListSource source = mock(SubjectListSource.class);
        List<SubjectSnapshot> external =
                List.of(snapshot("600519", "贵州茅台", "白酒"), snapshot("688011", "金山办公办公版", "办公软件"));
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK)).thenReturn(external);
        when(source.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));
        SubjectSyncService service = newService(source);

        MarketSyncResult first = service.syncAll().get(0);
        assertThat(first.inserted()).isEqualTo(1);

        // Act: 同轮重跑（无新上市、无变更）
        MarketSyncResult second = service.syncAll().get(0);

        // Assert: 写入差异为零（新增 0、更新 0；unchanged+1 是上一轮新增行归入不变集合）
        assertThat(second.inserted()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.unchanged()).isEqualTo(first.unchanged() + 1);
        assertThat(second.missing()).isEqualTo(first.missing());
        assertThat(second.total()).isEqualTo(first.total());
    }

    // ---- 缺失确认：只推进启用标的；回归清零 ----

    @Test
    void syncAll_missing_advancesEnabledOnly_thenReappearanceClearsStreak() {
        Subject enabledMissing =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SZ002345"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("消失观察标的")
                        .externalCodes(Map.of("eastmoney", "0.002345", "tushare", "002345.SZ"))
                        .build();
        subjectRepository.save(enabledMissing);

        SubjectListSource source = mock(SubjectListSource.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "贵州茅台", "白酒")));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));
        SubjectSyncService service = newService(source);

        // 第一轮：SZ002345 不在全量结果 → missing_streak 1（缺失确认只计数上报，停用动作 T52）
        service.syncAll();
        assertThat(streakOf("SZ002345")).isEqualTo(1);

        // 第二轮：仍缺失 → 2（每轮最多 +1）
        service.syncAll();
        assertThat(streakOf("SZ002345")).isEqualTo(2);

        // 第三轮：重新出现（名称/行业一致）→ 清零、状态仍启用、不算更新
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(
                        List.of(
                                snapshot("600519", "贵州茅台", "白酒"),
                                snapshot("002345", "消失观察标的", null)));
        MarketSyncResult third = service.syncAll().get(0);
        assertThat(streakOf("SZ002345")).isZero();
        assertThat(
                        subjectRepository
                                .findByCode(SubjectCode.of("SZ002345"))
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(SubjectStatus.ENABLED);
        assertThat(third.updated()).isZero();
    }

    // ---- T52 消失确认与停用 ----

    @Test
    void syncAll_streakReachesThreshold_deactivates_atThresholdOnly() {
        // 目标行：预推 2 轮（threshold-1）→ 本轮缺失第 3 轮 → 旧 streak+1=3 ≥ 3 停用
        Subject target = newStock("SH603777", "阈值停用验证");
        subjectRepository.save(target);
        subjectRepository.incrementMissingStreak("SH603777");
        subjectRepository.incrementMissingStreak("SH603777");
        // 对照行：首轮缺失（streak 0→1 < 3）→ 不停用
        subjectRepository.save(newStock("SH603778", "阈值未达验证"));

        SubjectListSource source = mock(SubjectListSource.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "贵州茅台", "白酒")));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));

        MarketSyncResult aShare = newService(source).syncAll().get(0);

        // 停用计数真实化：仅目标行翻转（其余缺失行 streak 0→1 未达阈值）
        assertThat(aShare.deactivated()).isEqualTo(1);
        assertThat(aShare.missing()).isGreaterThanOrEqualTo(2);
        Subject deactivatedTarget =
                subjectRepository.findByCode(SubjectCode.of("SH603777")).orElseThrow();
        assertThat(deactivatedTarget.getStatus()).isEqualTo(SubjectStatus.DISABLED);
        assertThat(deactivatedTarget.getMissingStreak()).isEqualTo(3);
        Subject belowThreshold =
                subjectRepository.findByCode(SubjectCode.of("SH603778")).orElseThrow();
        assertThat(belowThreshold.getStatus()).isEqualTo(SubjectStatus.ENABLED);
        assertThat(belowThreshold.getMissingStreak()).isEqualTo(1);
    }

    @Test
    void syncAll_marketFails_streakNotAdvanced_noFalseDeactivation() {
        // 预推到 threshold-1：若市场失败也推进计数，将本轮即假停用——流程 A 失败必须零写入（防假消失）
        subjectRepository.save(newStock("SH603779", "市场失败不假停用"));
        subjectRepository.incrementMissingStreak("SH603779");
        subjectRepository.incrementMissingStreak("SH603779");

        SubjectListSource source = mock(SubjectListSource.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException("clist 第 3 页拉取失败（重试耗尽）"));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));

        assertThatThrownBy(() -> newService(source).syncAll())
                .isInstanceOf(SubjectSyncException.class);

        Subject untouched = subjectRepository.findByCode(SubjectCode.of("SH603779")).orElseThrow();
        assertThat(untouched.getMissingStreak()).isEqualTo(2);
        assertThat(untouched.getStatus()).isEqualTo(SubjectStatus.ENABLED);
    }

    @Test
    void syncAll_deactivatedSubject_reappears_streakCleared_notRevived() {
        subjectRepository.save(newStock("SH603780", "停用后回归验证"));
        SubjectListSource source = mock(SubjectListSource.class);
        when(source.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));

        // 连续 3 轮缺失 → 达阈值停用（status 1→0）
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "贵州茅台", "白酒")));
        SubjectSyncService service = newService(source);
        service.syncAll();
        service.syncAll();
        service.syncAll();
        Subject deactivated =
                subjectRepository.findByCode(SubjectCode.of("SH603780")).orElseThrow();
        assertThat(deactivated.getStatus()).isEqualTo(SubjectStatus.DISABLED);

        // 回归：重新出现 → 名称照常更新、streak 清零（方案 §4.4 SQL ② 不筛 status——streak 为机制列，重新出现即断连续），
        // 但 status 不复活（ADR-0028 红线：更新不碰 status、单向 1→0）
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(
                        List.of(
                                snapshot("600519", "贵州茅台", "白酒"),
                                snapshot("603780", "停用后回归新名", null)));
        service.syncAll();
        Subject reappeared = subjectRepository.findByCode(SubjectCode.of("SH603780")).orElseThrow();
        assertThat(reappeared.getStatus()).as("停用标的不因回归复活").isEqualTo(SubjectStatus.DISABLED);
        assertThat(reappeared.getName()).isEqualTo("停用后回归新名");
        assertThat(reappeared.getMissingStreak()).isZero();

        // 再消失：已停用不再计数（collectMissing 跳过 status=0 行）
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "贵州茅台", "白酒")));
        service.syncAll();
        Subject stillDisabled =
                subjectRepository.findByCode(SubjectCode.of("SH603780")).orElseThrow();
        assertThat(stillDisabled.getStatus()).isEqualTo(SubjectStatus.DISABLED);
        assertThat(stillDisabled.getMissingStreak()).isZero();
    }

    // ---- 市场级原子 + 跨市场独立（§4.5） ----

    @Test
    void syncAll_hkFails_aShareCommitted_hkUntouchedAndStreakNotAdvanced() {
        SubjectListSource source = mock(SubjectListSource.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("688021", "新标的隔离验证", null)));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK))
                .thenThrow(new IllegalStateException("clist total 完整性校验失败 bucket=HK_STOCK"));
        SubjectSyncService service = newService(source);

        assertThatThrownBy(service::syncAll).isInstanceOf(SubjectSyncException.class);

        // A 股已生效（不回滚）
        assertThat(subjectRepository.findByCode(SubjectCode.of("SH688021"))).isPresent();
        // 港股既有种子（V2 腾讯控股）零触碰：市场失败不推进缺失计数、不改名称行业
        Subject tencent = subjectRepository.findByCode(SubjectCode.of("HK00700")).orElseThrow();
        assertThat(tencent.getName()).isEqualTo("腾讯控股");
        assertThat(tencent.getMissingStreak()).isZero();
    }

    // ---- T54 指数桶纳入（Should） ----

    @Test
    void syncAll_indexBucketEnabled_v17IndexSeedsAligned_noDuplicateInsert() {
        SubjectListSource source = mock(SubjectListSource.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "贵州茅台", "白酒")));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));
        // 指数桶源快照：4 只 V17 种子按代码对齐；f100 恒 "-" → industry null（§4.3 指数桶差异，行业以源为准）
        when(source.fetchAll(MarketSyncSpec.CN_INDEX))
                .thenReturn(
                        List.of(
                                indexSnapshot("000001", "上证指数", 1),
                                indexSnapshot("399001", "深证成指", 0),
                                indexSnapshot("399006", "创业板指", 0),
                                indexSnapshot("000300", "沪深300", 1)));

        List<MarketSyncResult> results = newService(source, true).syncAll();

        // 第 3 桶 = CN_INDEX：V17 种子按 subject_code 自然对齐，不重复插入；行业全量对齐源（null）→ updated=4
        MarketSyncResult index = results.get(2);
        assertThat(index.bucket()).isEqualTo(MarketSyncSpec.CN_INDEX);
        assertThat(index.inserted()).isZero();
        assertThat(index.updated()).isEqualTo(4);
        assertThat(index.missing()).isZero();
        assertThat(index.deactivated()).isZero();
        assertThat(index.total()).isEqualTo(4);

        // 桶规模不变（仍 4 行）+ market=INDEX / subject_type=2 映射核实 + 观察态零痕迹
        List<Subject> indexRows = subjectRepository.loadBucket(Market.INDEX, SubjectType.INDEX);
        assertThat(indexRows).hasSize(4);
        assertThat(indexRows)
                .allSatisfy(
                        row -> {
                            assertThat(row.getMarket()).isEqualTo(Market.INDEX);
                            assertThat(row.getSubjectType()).isEqualTo(SubjectType.INDEX);
                            assertThat(row.getStatus()).isEqualTo(SubjectStatus.ENABLED);
                            assertThat(row.getMissingStreak()).isZero();
                            assertThat(row.getIndustry()).as("行业以源为准（f100 占位归一 null）").isNull();
                            assertThat(row.getExternalCodes())
                                    .containsKey("eastmoney")
                                    .containsKey("tushare");
                        });

        // 还原 V17 种子行业（共享内存库不留脏数据）
        restoreIndexIndustry("SH000001", "上证指数", "综合指数", "1.000001", "000001.SH");
        restoreIndexIndustry("SZ399001", "深证成指", "综合指数", "0.399001", "399001.SZ");
        restoreIndexIndustry("SZ399006", "创业板指", "综合指数", "0.399006", "399006.SZ");
        restoreIndexIndustry("SH000300", "沪深300", "规模指数", "1.000300", "000300.SH");
    }

    // ---- helpers ----

    private SubjectSyncService newService(SubjectListSource source) {
        return newService(source, false);
    }

    private SubjectSyncService newService(SubjectListSource source, boolean indexEnabled) {
        return new SubjectSyncService(
                source,
                new SubjectSyncWriter(subjectRepository, TEST_BATCH_SIZE, TEST_STREAK_THRESHOLD),
                indexEnabled);
    }

    private void restoreIndexIndustry(
            String code, String name, String industry, String eastmoney, String tushare) {
        subjectRepository.updateSnapshot(
                code, name, industry, Map.of("eastmoney", eastmoney, "tushare", tushare));
    }

    /** 指数桶快照：f13 显式给定（沪指数 1./深指数 0.，代码首位无法区分市场，§4.2 桶表）。 */
    private static SubjectSnapshot indexSnapshot(String code, String name, int f13) {
        return new SubjectSnapshot(
                MarketSyncSpec.codePrefixOf(f13) + code,
                name,
                null,
                f13 + "." + code,
                MarketSyncSpec.CN_INDEX);
    }

    private int streakOf(String code) {
        Optional<Subject> found = subjectRepository.findByCode(SubjectCode.of(code));
        assertThat(found).as("标的应存在: " + code).isPresent();
        return found.orElseThrow().getMissingStreak();
    }

    /** 手工落库的启用沪市股票（T52 用例的观察对象）。 */
    private static Subject newStock(String code, String name) {
        return Subject.builder()
                .subjectCode(SubjectCode.of(code))
                .market(Market.A_SHARE)
                .subjectType(SubjectType.STOCK)
                .name(name)
                .externalCodes(
                        Map.of(
                                "eastmoney",
                                "1." + code.substring(2),
                                "tushare",
                                code.substring(2) + ".SH"))
                .build();
    }

    /** V2 港股种子（腾讯控股）对齐快照：成功轮内 unchanged，避免污染 HK00700 状态。 */
    private static SubjectSnapshot hkSeed() {
        return snapshot("00700", "腾讯控股", "互联网");
    }

    /** 代码约定：5 位港股（f13=116）/ 6 开头沪（1）/ 其余深（0）。 */
    private static SubjectSnapshot snapshot(String code, String name, String industry) {
        int f13 = code.length() == 5 ? 116 : code.startsWith("6") ? 1 : 0;
        return new SubjectSnapshot(
                MarketSyncSpec.codePrefixOf(f13) + code,
                name,
                industry,
                f13 + "." + code,
                f13 == 116 ? MarketSyncSpec.HK_STOCK : MarketSyncSpec.A_SHARE_STOCK);
    }
}
