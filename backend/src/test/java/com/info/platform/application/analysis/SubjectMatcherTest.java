package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SubjectMatcher 单测（T121，方案 §4.3）：池范围（A_SHARE+HK 的 STOCK、status=1、名长 ≥2）、标题/摘要命中、 名长降序前 N、TTL
 * 池缓存、池装载失败降级空池。mock SubjectRepository。AAA 结构。
 */
class SubjectMatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private SubjectRepository subjectRepository;
    private MutableClock clock;
    private SubjectMatcher matcher;

    /** 可拨动时钟（TTL 用例）。 */
    private static final class MutableClock extends Clock {

        private Instant now = NOW;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @BeforeEach
    void setUp() {
        subjectRepository = mock(SubjectRepository.class);
        clock = new MutableClock();
        matcher = new SubjectMatcher(subjectRepository, clock);
    }

    private static Subject subject(String code, String name, String industry, boolean enabled) {
        Subject subject =
                Subject.reconstruct(
                        null,
                        com.info.platform.domain.aggregation.SubjectCode.of(code),
                        Market.A_SHARE,
                        SubjectType.STOCK,
                        name,
                        null,
                        industry,
                        enabled
                                ? com.info.platform.domain.aggregation.SubjectStatus.ENABLED
                                : com.info.platform.domain.aggregation.SubjectStatus.DISABLED,
                        0,
                        null,
                        null);
        return subject;
    }

    private void stubPool(List<Subject> aShares, List<Subject> hks) {
        // doReturn 语法：thenThrow 桩后再换桩不触发真实调用（when() 内调用会吃到旧桩异常）；
        // M29 T253 池扩美股桶——本测试类不涉美股用例，第三桶桩空表（缺桩 null 会触发装载降级语义）
        stubPool(aShares, hks, List.of());
    }

    private void stubPool(List<Subject> aShares, List<Subject> hks, List<Subject> usShares) {
        org.mockito.Mockito.doReturn(aShares)
                .when(subjectRepository)
                .loadBucket(Market.A_SHARE, SubjectType.STOCK);
        org.mockito.Mockito.doReturn(hks)
                .when(subjectRepository)
                .loadBucket(Market.HK, SubjectType.STOCK);
        org.mockito.Mockito.doReturn(usShares)
                .when(subjectRepository)
                .loadBucket(Market.US, SubjectType.STOCK);
    }

    @Test
    void match_titleOrSummaryHit_returnsMatched() {
        stubPool(
                List.of(
                        subject("SH600519", "贵州茅台", "食品饮料", true),
                        subject("SH300750", "宁德时代", "电力设备", true)),
                List.of());

        List<SubjectMatcher.MatchedSubject> byTitle = matcher.match("贵州茅台发布半年度业绩预告", null);
        List<SubjectMatcher.MatchedSubject> bySummary = matcher.match("今日市场收评", "宁德时代动力电池装机量全球第一");

        assertThat(byTitle).hasSize(1);
        assertThat(byTitle.get(0).code()).isEqualTo("SH600519");
        assertThat(byTitle.get(0).industry()).isEqualTo("食品饮料");
        assertThat(bySummary).hasSize(1);
        assertThat(bySummary.get(0).name()).isEqualTo("宁德时代");
    }

    @Test
    void match_poolScope_filtersDisabledShortNameAndNonStockBuckets() {
        stubPool(
                List.of(
                        subject("SH600519", "贵州茅台", "食品饮料", true),
                        subject("SH600000", "浦发银行", null, false), // 停用不入池
                        subject("SH000001", "涨", null, true)), // 名长 <2 不入池
                List.of(subject("HK00700", "腾讯控股", null, true)));

        List<SubjectMatcher.MatchedSubject> matched = matcher.match("贵州茅台与腾讯控股达成战略合作", null);

        assertThat(matched)
                .extracting(SubjectMatcher.MatchedSubject::code)
                .containsExactly("SH600519", "HK00700"); // A_SHARE + HK 双桶，浦发/单字名不命中
    }

    @Test
    void match_cappedAtFive_longestNameFirst() {
        stubPool(
                List.of(
                        subject("SH600519", "贵州茅台", "食品饮料", true),
                        subject("SH600809", "山西汾酒", "食品饮料", true),
                        subject("SH000858", "五粮液", "食品饮料", true),
                        subject("SH600702", "舍得酒业", "食品饮料", true),
                        subject("SH603369", "今世缘", "食品饮料", true),
                        subject("SH600559", "老白干酒", "食品饮料", true)),
                List.of());

        List<SubjectMatcher.MatchedSubject> matched =
                matcher.match("贵州茅台五粮液山西汾酒舍得酒业今世缘老白干酒齐聚财报季", null);

        assertThat(matched).hasSize(SubjectMatcher.MAX_MATCHES_PER_ITEM); // 上限 5
        // 名长降序：4 字名（贵州茅台/山西汾酒/舍得酒业/老白干酒）先于 3 字名（五粮液/今世缘）
        assertThat(matched)
                .extracting(SubjectMatcher.MatchedSubject::name)
                .containsExactly("贵州茅台", "山西汾酒", "舍得酒业", "老白干酒", "五粮液");
    }

    @Test
    void match_poolCachedWithinTtl_reloadedAfterExpiry() {
        stubPool(List.of(subject("SH600519", "贵州茅台", "食品饮料", true)), List.of());
        assertThat(matcher.match("贵州茅台公告", null)).hasSize(1);

        // TTL 内换池：仍用旧快照（verify 不再触发 loadBucket——返回旧结果）
        stubPool(List.of(subject("SH300750", "宁德时代", "电力设备", true)), List.of());
        assertThat(matcher.match("宁德时代公告", null)).isEmpty();
        assertThat(matcher.match("贵州茅台公告", null)).hasSize(1);

        // TTL 过期后重载：新池生效
        clock.advance(Duration.ofMinutes(11));
        assertThat(matcher.match("宁德时代公告", null)).hasSize(1);
        assertThat(matcher.match("贵州茅台公告", null)).isEmpty();
    }

    @Test
    void match_poolLoadFailure_degradesToEmptyAndRetriesNextLoad() {
        when(subjectRepository.loadBucket(any(), any())).thenThrow(new RuntimeException("DB busy"));
        assertThat(matcher.match("贵州茅台公告", null)).isEmpty(); // 降级不抛

        // 恢复后可重载（TTL 过期）
        clock.advance(Duration.ofMinutes(11));
        stubPool(List.of(subject("SH600519", "贵州茅台", "食品饮料", true)), List.of());
        assertThat(matcher.match("贵州茅台公告", null)).hasSize(1);
    }

    @Test
    void match_usPoolSubject_hitCarriesMarketForL1Derivation() {
        // M29 T253：池扩美股桶——美股命中项携带 market=US（l1_market 主市场派生原料，方案 §4 C9）
        Subject apple =
                Subject.reconstruct(
                        null,
                        com.info.platform.domain.aggregation.SubjectCode.of("USAAPL"),
                        Market.US,
                        SubjectType.STOCK,
                        "苹果",
                        null,
                        "电子设备与元件",
                        com.info.platform.domain.aggregation.SubjectStatus.ENABLED,
                        0,
                        null,
                        null);
        stubPool(List.of(), List.of(), List.of(apple));

        var matched = matcher.match("苹果发布新款芯片", null);

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).market()).isEqualTo(Market.US);
        assertThat(matched.get(0).code()).isEqualTo("USAAPL");
        assertThat(matched.get(0).name()).isEqualTo("苹果");
        assertThat(matched.get(0).industry()).isEqualTo("电子设备与元件");
    }

    @Test
    void match_nullInputs_safeEmpty() {
        stubPool(List.of(subject("SH600519", "贵州茅台", "食品饮料", true)), List.of());
        assertThat(matcher.match(null, null)).isEmpty();
        assertThat(matcher.match("", "")).isEmpty();
    }
}
