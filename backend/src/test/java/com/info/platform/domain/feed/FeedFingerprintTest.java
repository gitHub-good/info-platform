package com.info.platform.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * FeedFingerprint 归一化矩阵单测（T104，ADR-0039 裁决 2 逐条可测）：NFKC 全半角/兼容分解、去全部空白（含全角空格）、ASCII casefold、
 * 同日同题跨源合并、跨日同题不合并、Asia/Shanghai 日期边界。
 */
class FeedFingerprintTest {

    private static final Instant MORNING = Instant.parse("2026-09-22T01:31:00Z");

    private static String fp(String title, Instant publishedAt) {
        return FeedFingerprint.fingerprint(title, publishedAt);
    }

    @Test
    void normalize_fullWidthCharacters_foldToHalfWidth() {
        // NFKC：全角２％/：→ 半角
        assertThat(fp("沪指涨２％", MORNING)).isEqualTo(fp("沪指涨2%", MORNING));
        assertThat(fp("中芯国际：财报发布", MORNING)).isEqualTo(fp("中芯国际:财报发布", MORNING));
    }

    @Test
    void normalize_compatibilityDecomposition_circledDigitsFold() {
        // NFKC 兼容分解：①②③ → 123
        assertThat(fp("三类信号①②③", MORNING)).isEqualTo(fp("三类信号123", MORNING));
    }

    @Test
    void normalize_whitespaceAllRemoved_includingIdeographicSpace() {
        assertThat(fp("Fed  Cuts Rates", MORNING)).isEqualTo(fp("FedCutsRates", MORNING));
        assertThat(fp("Fed\tCuts\r\n Rates", MORNING)).isEqualTo(fp("FedCutsRates", MORNING));
        // 全角空格 \u3000 与中文标题内空格均无语义
        assertThat(fp("沪指　上涨", MORNING)).isEqualTo(fp("沪指上涨", MORNING));
        assertThat(fp("沪指 上涨", MORNING)).isEqualTo(fp("沪指上涨", MORNING));
    }

    @Test
    void normalize_asciiCaseFolded() {
        assertThat(fp("APPLE Earnings Beat", MORNING))
                .isEqualTo(fp("apple earnings beat", MORNING));
    }

    @Test
    void sameTitleDifferentSources_sameDay_merged() {
        // 跨源同文去重：标题同 + 同上海日 → 指纹相同（首个入库者胜）。
        // 09-22 上海日 = [21T16:00:00Z, 22T15:59:59Z]
        assertThat(fp("美联储维持利率不变", MORNING))
                .isEqualTo(fp("美联储维持利率不变", Instant.parse("2026-09-22T15:59:59Z")));
    }

    @Test
    void sameTitleDifferentDays_notMerged() {
        // 跨日同题不合并（保守方向，宁漏并勿错并）
        assertThat(fp("美股收盘涨跌互现", MORNING))
                .isNotEqualTo(fp("美股收盘涨跌互现", MORNING.plusSeconds(86_400)));
    }

    @Test
    void shanghaiDateBoundary_isTheDaySplitLine() {
        // 15:59:59Z = 上海 23:59:59（09-21）；16:00:00Z = 上海 09-22 00:00 —— 同题跨线不合并
        String beforeBoundary = fp("夜盘要闻", Instant.parse("2026-09-21T15:59:59Z"));
        String afterBoundary = fp("夜盘要闻", Instant.parse("2026-09-21T16:00:00Z"));
        assertThat(beforeBoundary).isNotEqualTo(afterBoundary);
    }

    @Test
    void differentTitles_differentFingerprints() {
        assertThat(fp("标题甲", MORNING)).isNotEqualTo(fp("标题乙", MORNING));
    }

    @Test
    void nullTitle_treatedAsEmptyButDateStillGuards() {
        assertThat(fp(null, MORNING)).isEqualTo(fp("", MORNING));
        assertThat(fp(null, MORNING)).isNotEqualTo(fp(null, MORNING.plusSeconds(86_400)));
    }

    @Test
    void fingerprint_is64CharLowercaseHex() {
        String fingerprint = fp("格式校验", MORNING);

        assertThat(fingerprint).matches("[0-9a-f]{64}");
    }
}
