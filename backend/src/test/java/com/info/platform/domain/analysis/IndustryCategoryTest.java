package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
import org.junit.jupiter.api.Test;

/**
 * IndustryCategory 单测（T121，ADR-0046 裁决 3）：35 枚举清单冻结值逐项断言（申万 31 + 容器 4）、白名单判定、 申万/容器区分（热度聚合 范围与 sub
 * 校验的权威面）。AAA 结构，纯常量零依赖。
 */
class IndustryCategoryTest {

    @Test
    void catalog_totalSizeIs35() {
        assertThat(IndustryCategory.SW_INDUSTRIES).hasSize(31);
        assertThat(IndustryCategory.CONTAINERS).hasSize(4);
        assertThat(IndustryCategory.totalSize()).isEqualTo(35);
    }

    @Test
    void catalog_swListMatchesFrozenDesign() {
        // 方案 §2/§4.3 冻结清单（申万一级行业 31 个）
        assertThat(IndustryCategory.SW_INDUSTRIES)
                .containsExactlyInAnyOrder(
                        "农林牧渔", "基础化工", "钢铁", "有色金属", "电子", "家用电器", "食品饮料", "纺织服饰", "轻工制造", "医药生物",
                        "公用事业", "交通运输", "房地产", "商贸零售", "社会服务", "银行", "非银金融", "综合", "建筑材料", "建筑装饰",
                        "电力设备", "机械设备", "国防军工", "计算机", "传媒", "通信", "煤炭", "石油石化", "环保", "美容护理",
                        "汽车");
    }

    @Test
    void catalog_containersMatchFrozenDesign() {
        assertThat(IndustryCategory.CONTAINERS)
                .containsExactlyInAnyOrder("宏观", "监管·政策", "国际", "市场·其他");
    }

    @Test
    void isValid_acceptsAll35AndRejectsOthers() {
        assertThat(IndustryCategory.isValid("宏观")).isTrue();
        assertThat(IndustryCategory.isValid("市场·其他")).isTrue();
        assertThat(IndustryCategory.isValid("食品饮料")).isTrue();
        // 非法枚举（模板漂移信号）：编造分类/近似名/空值全拒绝
        assertThat(IndustryCategory.isValid("太空采矿")).isFalse();
        assertThat(IndustryCategory.isValid("食品 饮料")).isFalse();
        assertThat(IndustryCategory.isValid("")).isFalse();
        assertThat(IndustryCategory.isValid(null)).isFalse();
    }

    @Test
    void isSwIndustry_containersExcluded() {
        // 容器不进榜（热度聚合范围）；sub 次行业只允许申万枚举（方案 §4.3 规则 4）
        assertThat(IndustryCategory.isSwIndustry("银行")).isTrue();
        assertThat(IndustryCategory.isSwIndustry("宏观")).isFalse();
        assertThat(IndustryCategory.isSwIndustry("市场·其他")).isFalse();
        assertThat(IndustryCategory.isSwIndustry(null)).isFalse();
    }

    // ---- M29 T251：港美股枚举集（ADR-0064 裁决 2，各 ≤40 + UNKNOWN）----

    @Test
    void m29_hkAndUsSets_within40Cap_andDisjointFromUnknown() {
        // 港 31 直采 / 美 156 词归并 40 大类（T251 首跑词频定稿）；白名单集不含 UNKNOWN（判定时另算）
        assertThat(IndustryCategory.hkSize()).isEqualTo(31);
        assertThat(IndustryCategory.usSize()).isEqualTo(40);
        assertThat(IndustryCategory.HK_INDUSTRIES)
                .doesNotContain(IndustryCategory.UNKNOWN_INDUSTRY);
        assertThat(IndustryCategory.US_INDUSTRIES)
                .doesNotContain(IndustryCategory.UNKNOWN_INDUSTRY);
    }

    @Test
    void m29_isValidByMarket_hkAndUsWhitelists() {
        // 港股：直采词收 / 未收录词拒 / UNKNOWN 收（兜底枚举）
        assertThat(IndustryCategory.isValid(Market.HK, "软件服务")).isTrue();
        assertThat(IndustryCategory.isValid(Market.HK, "食品饮料")).isFalse(); // 申万词不串门
        assertThat(IndustryCategory.isValid(Market.HK, IndustryCategory.UNKNOWN_INDUSTRY)).isTrue();
        assertThat(IndustryCategory.isValid(Market.HK, null)).isFalse();
        // 美股：归并枚举收 / F10 原词（未归并形态）拒 / UNKNOWN 收
        assertThat(IndustryCategory.isValid(Market.US, "电子设备与元件")).isTrue();
        assertThat(IndustryCategory.isValid(Market.US, "电脑硬件、储存设备及电脑周边")).isFalse();
        assertThat(IndustryCategory.isValid(Market.US, IndustryCategory.UNKNOWN_INDUSTRY)).isTrue();
        // A 股（含 null market 缺省）：既有 35 枚举口径零变化
        assertThat(IndustryCategory.isValid(Market.A_SHARE, "银行")).isTrue();
        assertThat(IndustryCategory.isValid(null, "银行")).isTrue();
        assertThat(IndustryCategory.isValid(Market.A_SHARE, "软件服务")).isFalse();
        assertThat(IndustryCategory.isValid(Market.A_SHARE, IndustryCategory.UNKNOWN_INDUSTRY))
                .isFalse(); // UNKNOWN 为港美股口径，A 股不收
    }

    @Test
    void m29_crossMarketSameName_isAllowed_marketDisambiguates() {
        // 跨市场重名（银行/综合企业/汽车等）由 market 消歧——各自白名单独立收词（ADR-0064 裁决 2）
        assertThat(IndustryCategory.isValid(Market.HK, "银行")).isTrue();
        assertThat(IndustryCategory.isValid(Market.US, "银行")).isTrue();
        assertThat(IndustryCategory.isValid(Market.A_SHARE, "银行")).isTrue();
    }

    @Test
    void m29_boardIndustry_marketScopedWhitelist() {
        // 进榜白名单统一口径：A 股=申万 31（容器不进）、HK/US=各自枚举（UNKNOWN 与容器不进）
        assertThat(IndustryCategory.isBoardIndustry(Market.A_SHARE, "银行")).isTrue();
        assertThat(IndustryCategory.isBoardIndustry(Market.A_SHARE, "宏观")).isFalse();
        assertThat(IndustryCategory.isBoardIndustry(Market.HK, "药品及生物科技")).isTrue();
        assertThat(IndustryCategory.isBoardIndustry(Market.HK, "食品饮料")).isFalse(); // 申万词不入港枚举
        assertThat(IndustryCategory.isBoardIndustry(Market.US, "互联网与数字媒体")).isTrue();
        assertThat(IndustryCategory.isBoardIndustry(Market.US, "UNKNOWN")).isFalse();
        assertThat(IndustryCategory.isBoardIndustry(Market.HK, null)).isFalse();
        // 跨市场重名进榜判定互不串集
        assertThat(IndustryCategory.isBoardIndustry(Market.US, "软件服务")).isFalse();
    }

    @Test
    void m29_industrySystem_marketScopedLabels() {
        assertThat(IndustryCategory.industrySystemOf(Market.A_SHARE)).isEqualTo("A股：申万一级 31");
        assertThat(IndustryCategory.industrySystemOf(Market.HK)).contains("港股").contains("31");
        assertThat(IndustryCategory.industrySystemOf(Market.US)).contains("美股").contains("40");
    }
}
