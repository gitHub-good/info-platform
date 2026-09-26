package com.info.platform.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * IndustryDirectory 单测（T131，ADR-0051 裁决 4 / 方案 §4.9）：31 行业全覆盖、别名命中矩阵（P2 通道 A 主力路径）、无映射主题不入集 （走
 * P3）、反查（isDirectoryWord/industryOfWord）、目录词与申万枚举一致性。 AAA 结构，纯常量零依赖。
 */
class IndustryDirectoryTest {

    // ---- 目录完整性 ----

    @Test
    void directory_coversAllThirtyOneSwIndustries() {
        assertThat(IndustryDirectory.industryCount()).isEqualTo(31);
        assertThat(IndustryDirectory.coversAllSwIndustries())
                .as("目录行业集 = 申万 31 枚举（与 IndustryCategory.SW_INDUSTRIES 一致）")
                .isTrue();
    }

    @Test
    void directory_aliasVolume_nearPlanScale() {
        // 方案口径 ~31×4 词（含行业名 31 + 别名 ~150，量级守护非精确断言）
        assertThat(IndustryDirectory.allWords().size()).isBetween(150, 220);
    }

    // ---- 通道 A 映射命中矩阵 ----

    @Test
    void industriesOf_aliasHits_mapToParentIndustry() {
        // 方案 §3.4 示例：订阅「半导体」→ 电子；「创新药」→ 医药生物
        assertThat(IndustryDirectory.industriesOf("半导体")).containsExactly("电子");
        assertThat(IndustryDirectory.industriesOf("创新药")).containsExactly("医药生物");
        assertThat(IndustryDirectory.industriesOf("芯片国产替代")).containsExactly("电子");
        assertThat(IndustryDirectory.industriesOf("白酒")).containsExactly("食品饮料");
        assertThat(IndustryDirectory.industriesOf("券商合并传闻")).containsExactly("非银金融");
        assertThat(IndustryDirectory.industriesOf("光伏产业链")).containsExactly("电力设备");
        assertThat(IndustryDirectory.industriesOf("军工")).containsExactly("国防军工");
        assertThat(IndustryDirectory.industriesOf("存储芯片")).containsExactly("电子");
    }

    @Test
    void industriesOf_industryNameItself_hits() {
        // 行业名本身也是匹配词：订阅「电子行业动态」→ 电子
        assertThat(IndustryDirectory.industriesOf("电子行业动态")).containsExactly("电子");
        assertThat(IndustryDirectory.industriesOf("医药生物")).containsExactly("医药生物");
    }

    @Test
    void industriesOf_multiWordSubKey_mapsMultipleIndustries() {
        // 一个 subKey 命中多行业（contains 多词）→ 全部入集（P2 命中面求并）
        assertThat(IndustryDirectory.industriesOf("半导体与白酒板块"))
                .containsExactlyInAnyOrder("电子", "食品饮料");
    }

    @Test
    void industriesOf_noMappingWord_emptySet_walksP3() {
        // 方案 §3.4 示例：订阅「货币政策」→ 无映射 → 不入集（该订阅走 P3 主题命中）
        assertThat(IndustryDirectory.industriesOf("货币政策")).isEmpty();
        assertThat(IndustryDirectory.industriesOf("美联储加息")).isEmpty();
        assertThat(IndustryDirectory.industriesOf(null)).isEmpty();
        assertThat(IndustryDirectory.industriesOf("  ")).isEmpty();
    }

    @Test
    void industriesOf_noCrossMappingBetweenAmbiguousSiblings() {
        // 歧义护栏：行业名不含他行业别名（电力设备 不映射 公用事业；新能源汽车 归 汽车）
        assertThat(IndustryDirectory.industriesOf("电力设备")).doesNotContain("公用事业");
        assertThat(IndustryDirectory.industriesOf("新能源汽车")).contains("汽车");
        assertThat(IndustryDirectory.industriesOf("新能源汽车")).doesNotContain("电力设备");
    }

    // ---- 反查（themeHit 的行业别名反查路径）----

    @Test
    void industryOfWord_reverseLookup() {
        assertThat(IndustryDirectory.industryOfWord("半导体")).isEqualTo("电子");
        assertThat(IndustryDirectory.industryOfWord("电子")).isEqualTo("电子");
        assertThat(IndustryDirectory.industryOfWord("CXO")).isEqualTo("医药生物");
        assertThat(IndustryDirectory.industryOfWord("货币政策")).isNull();
        assertThat(IndustryDirectory.industryOfWord(null)).isNull();
        assertThat(IndustryDirectory.isDirectoryWord("锂电池")).isTrue();
        assertThat(IndustryDirectory.isDirectoryWord("不存在的词")).isFalse();
    }
}
