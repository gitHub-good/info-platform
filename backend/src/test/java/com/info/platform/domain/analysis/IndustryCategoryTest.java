package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

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
}
