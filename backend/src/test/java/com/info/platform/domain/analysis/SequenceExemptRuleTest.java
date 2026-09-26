package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SequenceExemptRule 单测（T130 BUG-03，M16 方案 §4.2）：两谓词（externalId 含 # / 标题「YYYY年X月份」月度模式）各自命中、 双命中取
 * ①（# 优先留痕）、双不命中不豁免、月值边界（1~12 含/不含前导零，13/00 不匹配）、年界（19xx/20xx 匹配、21xx 不匹配）、null 安全。 AAA 结构，纯函数零依赖。
 */
class SequenceExemptRuleTest {

    // ---- 谓词 ①：externalId 含 # ----

    @Test
    void exemptToken_volumeMarkedInExternalId_exemptWithVolumeToken() {
        // BUG-03 实证形态：RPT_ECONOMY_CPI#2026-08-01（数据源分卷编号）
        assertThat(SequenceExemptRule.exemptToken("RPT_ECONOMY_CPI#2026-08-01", "任意标题"))
                .isEqualTo("seq-exempt:#");
        assertThat(SequenceExemptRule.exemptToken("#", null)).isEqualTo("seq-exempt:#");
    }

    @Test
    void exemptToken_externalIdWithoutMark_notExemptById() {
        assertThat(SequenceExemptRule.exemptToken("RPT_ECONOMY_CPI_2026-08-01", "CPI 数据发布"))
                .isNull();
        assertThat(SequenceExemptRule.exemptToken("", "CPI 数据发布")).isNull();
        assertThat(SequenceExemptRule.exemptToken(null, "CPI 数据发布")).isNull();
    }

    // ---- 谓词 ②：月度标题模式 ----

    @Test
    void exemptToken_monthlyTitlePattern_exemptWithMonthlyToken() {
        // M15 实测样本形态：CPI：2026年08月份 同比 0.8%（含前导零）
        assertThat(SequenceExemptRule.exemptToken(null, "CPI：2026年08月份 同比 0.8%"))
                .isEqualTo("seq-exempt:monthly");
        // 不含前导零
        assertThat(SequenceExemptRule.exemptToken(null, "2026年8月份 PPI 同比数据公布"))
                .isEqualTo("seq-exempt:monthly");
        // 尾月/首月
        assertThat(SequenceExemptRule.exemptToken(null, "LPR：2025年12月份 报价出炉"))
                .isEqualTo("seq-exempt:monthly");
        assertThat(SequenceExemptRule.exemptToken(null, "LPR：2025年1月份 报价出炉"))
                .isEqualTo("seq-exempt:monthly");
        // 19xx 年界内
        assertThat(SequenceExemptRule.exemptToken(null, "1999年11月份 工业增加值数据"))
                .isEqualTo("seq-exempt:monthly");
    }

    @Test
    void exemptToken_nonMonthlyTitles_notExempt() {
        assertThat(SequenceExemptRule.exemptToken(null, "央行宣布下调存款准备金率0.5个百分点")).isNull();
        // 月值越界：13 月 / 00 月
        assertThat(SequenceExemptRule.exemptToken(null, "2026年13月份 数据")).isNull();
        assertThat(SequenceExemptRule.exemptToken(null, "2026年00月份 数据")).isNull();
        // 年界外：21xx
        assertThat(SequenceExemptRule.exemptToken(null, "2100年1月份 数据")).isNull();
        // 「年/月」非「月份」措辞（非月度序列模板）
        assertThat(SequenceExemptRule.exemptToken(null, "2026年08月 CPI 数据回顾")).isNull();
        assertThat(SequenceExemptRule.exemptToken(null, null)).isNull();
        assertThat(SequenceExemptRule.isExempt(null, "")).isFalse();
    }

    // ---- 双命中与布尔视图 ----

    @Test
    void exemptToken_bothPredicatesHit_volumeTokenWins() {
        // 双命中取 ①（# 是数据源显式信号，留痕更精确）
        assertThat(SequenceExemptRule.exemptToken("X#1", "CPI：2026年08月份 同比 0.8%"))
                .isEqualTo("seq-exempt:#");
        assertThat(SequenceExemptRule.isExempt("X#1", "CPI：2026年08月份 同比 0.8%")).isTrue();
    }
}
