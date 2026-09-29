package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
import org.junit.jupiter.api.Test;

/**
 * IndustryEnumMapper 单测（M29 T251，ADR-0064 裁决 2）：F10 原词 → 港美股枚举归并——港股 31 词恒等直采、美股 156 词大类 归并
 * 40、双兜底（null/空白/未收录 → UNKNOWN）、全量映射值恒在白名单（构造性不变量，防手改映射漂移）。AAA 结构，纯常量零依赖。
 */
class IndustryEnumMapperTest {

    @Test
    void map_hk_identityForDirectlyAdoptedWords() {
        // 港股 ≤40 直采：原词即枚举（Spike-E E-3a 实测词）
        assertThat(IndustryEnumMapper.map(Market.HK, "软件服务")).isEqualTo("软件服务");
        assertThat(IndustryEnumMapper.map(Market.HK, "黄金及贵金属")).isEqualTo("黄金及贵金属");
    }

    @Test
    void map_us_mergesRawWordsIntoMergedEnums() {
        // 156 词 → 40 大类的代表性归并（Spike-E E-3a + T251 首跑词频）
        assertThat(IndustryEnumMapper.map(Market.US, "电脑硬件、储存设备及电脑周边")).isEqualTo("电子设备与元件");
        assertThat(IndustryEnumMapper.map(Market.US, "应用软件")).isEqualTo("软件与信息服务");
        assertThat(IndustryEnumMapper.map(Market.US, "系统软件")).isEqualTo("软件与信息服务"); // 同枚举多词归并
        assertThat(IndustryEnumMapper.map(Market.US, "生命科学工具和服务")).isEqualTo("医疗保健设备与服务");
        assertThat(IndustryEnumMapper.map(Market.US, "区域性银行")).isEqualTo("银行");
    }

    @Test
    void map_nullBlankOrUnknownWord_fallsBackToUnknown() {
        // 双兜底（方案 R4）：null / 空白 / 未收录新词（源词表演化）→ UNKNOWN，不失败整轮
        assertThat(IndustryEnumMapper.map(Market.HK, null)).isEqualTo("UNKNOWN");
        assertThat(IndustryEnumMapper.map(Market.US, "  ")).isEqualTo("UNKNOWN");
        assertThat(IndustryEnumMapper.map(Market.HK, "新出现的行业词")).isEqualTo("UNKNOWN");
        assertThat(IndustryEnumMapper.map(Market.US, "太空采矿")).isEqualTo("UNKNOWN");
    }

    @Test
    void map_nonHkUsMarket_passesThroughDefensively() {
        // A 股行业不经本映射器（f100 直落）：防御直通原值（含 null）
        assertThat(IndustryEnumMapper.map(Market.A_SHARE, "食品饮料")).isEqualTo("食品饮料");
        assertThat(IndustryEnumMapper.map(null, null)).isNull();
    }

    @Test
    void usMapping_fullCoverage_valuesAlwaysInWhitelist() {
        // 构造性不变量：156 词全量覆盖，且每个归并值都在 US_INDUSTRIES 白名单内（map() 输出恒合法枚举）
        assertThat(IndustryEnumMapper.usMapping()).hasSize(156);
        IndustryEnumMapper.usMapping()
                .forEach(
                        (raw, enumName) ->
                                assertThat(enumName)
                                        .as("原词 %s 的归并目标", raw)
                                        .isIn(IndustryCategory.US_INDUSTRIES));
        // 归并目标集 = 白名单全集（40 大类每个都有词归入，无空枚举）
        assertThat(IndustryEnumMapper.usMapping().values().stream().distinct().count())
                .isEqualTo(IndustryCategory.usSize());
    }
}
