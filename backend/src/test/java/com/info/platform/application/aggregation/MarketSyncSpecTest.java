package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SubjectType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MarketSyncSpec / SubjectSnapshot 单测（T50）：桶定义契约（fs/市场/类型，§4.2 桶表）、f13 → 前缀映射三市场、 external_codes
 * 双键派生（对齐 V2/V17 JSON 形态）与非法输入防御。
 */
class MarketSyncSpecTest {

    @Test
    void buckets_matchDesignTable() {
        // §4.2 桶表（fs 权威定义提取自东财行情中心页面 JS，ADR-0027）
        assertThat(MarketSyncSpec.A_SHARE_STOCK.fs())
                .isEqualTo("m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23");
        assertThat(MarketSyncSpec.A_SHARE_STOCK.market()).isEqualTo(Market.A_SHARE);
        assertThat(MarketSyncSpec.A_SHARE_STOCK.subjectType()).isEqualTo(SubjectType.STOCK);

        assertThat(MarketSyncSpec.HK_STOCK.fs()).isEqualTo("m:116+t:3,m:116+t:4");
        assertThat(MarketSyncSpec.HK_STOCK.market()).isEqualTo(Market.HK);
        assertThat(MarketSyncSpec.HK_STOCK.subjectType()).isEqualTo(SubjectType.STOCK);

        // 指数桶（T54 接入，本批仅预留定义）
        assertThat(MarketSyncSpec.CN_INDEX.fs()).isEqualTo("m:1+t:1,m:0+t:5");
        assertThat(MarketSyncSpec.CN_INDEX.market()).isEqualTo(Market.INDEX);
        assertThat(MarketSyncSpec.CN_INDEX.subjectType()).isEqualTo(SubjectType.INDEX);
    }

    @Test
    void codePrefixOf_mapsThreeMarkets() {
        // §4.2 映射表：1=沪 / 0=深 / 116=港（f13 恰为 secid 市场前缀本身）
        assertThat(MarketSyncSpec.codePrefixOf(1)).isEqualTo("SH");
        assertThat(MarketSyncSpec.codePrefixOf(0)).isEqualTo("SZ");
        assertThat(MarketSyncSpec.codePrefixOf(116)).isEqualTo("HK");
    }

    @Test
    void codePrefixOf_unknownFlag_failsFast() {
        // 防御：源口径漂移（未知市场码）抛异常而非错配前缀
        assertThatThrownBy(() -> MarketSyncSpec.codePrefixOf(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void snapshot_externalCodes_dualKey() {
        // A股：{"eastmoney":"1.600519","tushare":"600519.SH"}（V2/V17 种子同形态）
        SubjectSnapshot aShare =
                new SubjectSnapshot(
                        "SH600519", "贵州茅台", "白酒", "1.600519", MarketSyncSpec.A_SHARE_STOCK);
        assertThat(aShare.externalCodes())
                .isEqualTo(Map.of("eastmoney", "1.600519", "tushare", "600519.SH"));

        // 港股：{"eastmoney":"116.00700","tushare":"00700.HK"}
        SubjectSnapshot hk =
                new SubjectSnapshot("HK00700", "腾讯控股", null, "116.00700", MarketSyncSpec.HK_STOCK);
        assertThat(hk.externalCodes())
                .isEqualTo(Map.of("eastmoney", "116.00700", "tushare", "00700.HK"));

        // 深市
        SubjectSnapshot sz =
                new SubjectSnapshot(
                        "SZ000001", "平安银行", "银行", "0.000001", MarketSyncSpec.A_SHARE_STOCK);
        assertThat(sz.externalCodes()).containsEntry("tushare", "000001.SZ");
    }

    @Test
    void snapshot_constructor_validatesRequiredFields() {
        MarketSyncSpec bucket = MarketSyncSpec.A_SHARE_STOCK;
        assertThatThrownBy(() -> new SubjectSnapshot(null, "名", null, "1.600519", bucket))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SubjectSnapshot("SH600519", null, null, "1.600519", bucket))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SubjectSnapshot("SH600519", "名", null, null, bucket))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SubjectSnapshot("SH600519", "名", null, "1.600519", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void snapshot_externalCodes_malformedSecid_failsFast() {
        SubjectSnapshot malformed =
                new SubjectSnapshot(
                        "SH600519", "贵州茅台", null, "1600519", MarketSyncSpec.A_SHARE_STOCK);
        // 防御：secid 非 f13.f12 形态（无 '.'）→ 派生失败快速暴露而非静默错键
        assertThatThrownBy(malformed::externalCodes).isInstanceOf(IllegalStateException.class);
        SubjectSnapshot trailingDot =
                new SubjectSnapshot("SH600519", "贵州茅台", null, "1.", MarketSyncSpec.A_SHARE_STOCK);
        assertThatThrownBy(trailingDot::externalCodes).isInstanceOf(IllegalStateException.class);
    }
}
