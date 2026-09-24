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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * SubjectSyncService 引擎集成测试（T51）：真实 SQLite 共享内存库（V18 列已建）+ mock 列表源（不真实外呼 push2）。 手工装配 batch-size=2
 * 的 writer 验证批量边界；覆盖 §6 引擎 diff 要点—— 首轮建池（种子行不变 + 新增插入）/ 更新不碰 status（停用标的照常更新名称行业）/ 幂等二轮差异为零 /
 * 缺失计数只推进启用行 / 回归清零 / 市场失败不推进计数且跨市场独立（A 股生效、港股放弃）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SubjectSyncServiceIntegrationTest {

    /** 手工装配的写库批量（防长事务分批的边界用 2 触发：5 只新标的 → 3 批）。 */
    private static final int TEST_BATCH_SIZE = 2;

    @Autowired private SubjectRepository subjectRepository;

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

    // ---- helpers ----

    private SubjectSyncService newService(SubjectListSource source) {
        return new SubjectSyncService(
                source, new SubjectSyncWriter(subjectRepository, TEST_BATCH_SIZE));
    }

    private int streakOf(String code) {
        Optional<Subject> found = subjectRepository.findByCode(SubjectCode.of(code));
        assertThat(found).as("标的应存在: " + code).isPresent();
        return found.orElseThrow().getMissingStreak();
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
