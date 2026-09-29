package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.MarketSyncResult;
import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.application.aggregation.SubjectSyncService;
import com.info.platform.application.aggregation.SubjectSyncWriter;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 备选源切换的引擎级集成测试（M7，ADR-0030；M29 T251 扩港美股 F10 路由）：真实 SQLite 共享内存库 + 真实路由（auto）+ 真实
 * SubjectSyncService/SubjectSyncWriter，只 mock HTTP 客户端（东财 clist / 新浪 / 东财 F10——不真实外呼）。
 *
 * <p>覆盖验收主场景：push2 封禁（东财 A 股桶拉取失败）下 auto 自动 fallback 新浪整桶重拉——整轮 syncAll 成功、A 股计数与入库行均来自新浪快照（含
 * secid/取数键派生对齐东财形态）；港美股桶走 F10 通道不受切换影响（M29 T251：routing 港美股恒 F10）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RoutingSubjectListSourceIntegrationTest {

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetStockObservationState() {
        // 股票桶观察态复位（对齐 SubjectSyncServiceIntegrationTest 的共享库隔离约定）
        jdbcTemplate.update(
                "UPDATE subject_master SET missing_streak = 0, status = 1 "
                        + "WHERE subject_type = 1 AND market IN ('A_SHARE', 'HK', 'US')");
    }

    @Test
    void syncAll_eastMoneyAFails_autoFallsBackToSina_aShareApplied() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        EastMoneyF10ListClient f10 = mock(EastMoneyF10ListClient.class);
        // 东财 A 股桶失败（push2 IP 封禁的典型形态：重试耗尽）；港美股桶走 F10 正常
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException("clist 第 1 页拉取失败（重试耗尽）bucket=A_SHARE_STOCK"));
        when(f10.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(List.of(hkSeed()));
        when(f10.fetchAll(MarketSyncSpec.US_STOCK)).thenReturn(List.of(usSeed()));
        // 新浪备选源全量（行业恒 null、secid 派生对齐东财形态）
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(
                        List.of(
                                sinaSnapshot("sh600519", "1.600519", "贵州茅台"),
                                sinaSnapshot("sz000001", "0.000001", "平安银行"),
                                sinaSnapshot("sh688006", "1.688006", "百奥泰")));
        SubjectSyncService service =
                new SubjectSyncService(
                        new RoutingSubjectListSource(eastMoney, sina, f10, "auto", null),
                        new SubjectSyncWriter(subjectRepository, 500, 3),
                        false);

        List<MarketSyncResult> results = service.syncAll();

        // 整轮成功：A 股桶生效（新浪全量）+ 港美股桶生效（F10 通道，切换不外溢）
        assertThat(results).hasSize(3);
        MarketSyncResult aShare = results.get(0);
        assertThat(aShare.bucket()).isEqualTo(MarketSyncSpec.A_SHARE_STOCK);
        assertThat(aShare.total()).isEqualTo(3);
        assertThat(aShare.inserted()).isEqualTo(1); // 仅 688006 新增；600519/000001 为 V2 种子行
        // PM 裁「以源为准」：新浪无行业字段 → 种子行的东财行业被置 null（计入更新，ADR-0030）
        assertThat(aShare.updated()).isEqualTo(2);
        assertThat(results.get(1).bucket()).isEqualTo(MarketSyncSpec.HK_STOCK);
        assertThat(results.get(2).bucket()).isEqualTo(MarketSyncSpec.US_STOCK);

        // 新浪快照入库行带东财 secid/双取数键（行情/估值链路零改造）
        Subject listed = subjectRepository.findByCode(SubjectCode.of("SH688006")).orElseThrow();
        assertThat(listed.getName()).isEqualTo("百奥泰");
        assertThat(listed.getIndustry()).isNull();
        assertThat(listed.getExternalCodes())
                .containsEntry("eastmoney", "1.688006")
                .containsEntry("tushare", "688006.SH");
        // F10 美股快照入库：US 前缀 + 三取数键（eastmoney/tushare/f10）
        Subject usListed = subjectRepository.findByCode(SubjectCode.of("USAAPL")).orElseThrow();
        assertThat(usListed.getMarket()).isEqualTo(com.info.platform.domain.aggregation.Market.US);
        assertThat(usListed.getIndustry()).isEqualTo("电子设备与元件");
        assertThat(usListed.getExternalCodes())
                .containsEntry("eastmoney", "105.AAPL")
                .containsEntry("tushare", "AAPL.US")
                .containsEntry("f10", "AAPL.O");
        // 种子行走「不变/更新」而非误缺失（新浪全量覆盖了 V2 种子口径）
        assertThat(
                        subjectRepository
                                .findByCode(SubjectCode.of("SH600519"))
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(com.info.platform.domain.aggregation.SubjectStatus.ENABLED);

        // 还原共享内存库观察态（美股新增行清除 + 港股种子行还原 F10 快照前形态，不留脏数据）
        jdbcTemplate.update("DELETE FROM subject_master WHERE market = 'US'");
        subjectRepository.updateSnapshot(
                "HK00700",
                "腾讯控股",
                "互联网",
                java.util.Map.of("eastmoney", "116.00700", "tushare", "00700.HK"));
    }

    // ---- helpers ----

    /** 新浪形态快照：industry 恒 null、secid 沪 1./深 0. 前缀（与东财快照同形态）。 */
    private static SubjectSnapshot sinaSnapshot(String symbol, String secid, String name) {
        return new SubjectSnapshot(
                symbol.substring(0, 2).toUpperCase() + symbol.substring(2),
                name,
                null,
                secid,
                MarketSyncSpec.A_SHARE_STOCK);
    }

    private static SubjectSnapshot hkSeed() {
        return new SubjectSnapshot(
                "HK00700", "腾讯控股", "软件服务", "116.00700", MarketSyncSpec.HK_STOCK, "00700.HK");
    }

    /** F10 美股快照（M29 T251）：US 前缀 + 105 secid + f10 原键 + 归并枚举行业。 */
    private static SubjectSnapshot usSeed() {
        return new SubjectSnapshot(
                "USAAPL", "苹果", "电子设备与元件", "105.AAPL", MarketSyncSpec.US_STOCK, "AAPL.O");
    }
}
