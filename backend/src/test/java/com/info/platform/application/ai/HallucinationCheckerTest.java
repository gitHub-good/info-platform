package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.ai.BriefFact;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HallucinationChecker 单测（T21）：metric 回查 + 相对误差 + 待核实标记，AAA 结构。
 *
 * <p>mock {@link SourceAdapter}（FINANCE 源）；覆盖：数值吻合（VERIFIED）/ 数值不符超 5%（NEED_VERIFY）/
 * 源降级（NEED_VERIFY 源暂不可用）/ 字段缺失（NEED_VERIFY）/ 定性事实无数值（SKIPPED）/ 未映射 metric（SKIPPED）。 不依赖真实 LLM API
 * key。
 */
class HallucinationCheckerTest {

    private SourceAdapter financeAdapter;
    private HallucinationChecker checker;
    private Subject subject;

    @BeforeEach
    void setUp() {
        financeAdapter = mock(SourceAdapter.class);
        when(financeAdapter.sourceCode()).thenReturn(SourceCode.FINANCE);
        checker = new HallucinationChecker(List.of(financeAdapter), 0.05);
        subject = subject();
    }

    @Test
    void check_valueMatchesTrueValue_withinTolerance_verified() {
        // Arrange：真值 30.55，模型 30.5 → 相对误差 0.16% < 5%
        when(financeAdapter.fetch(subject)).thenReturn(ok(Map.of("roe", new BigDecimal("30.55"))));
        BriefFact fact = new BriefFact("ROE 30.5%", "roe", 30.5, "FINANCE", "http://f");

        // Act
        HallucinationResult result = checker.check(List.of(fact), subject);

        // Assert
        assertThat(result.needVerify()).isFalse();
        assertThat(result.checks()).hasSize(1);
        assertThat(result.checks().get(0).status()).isEqualTo(FactCheckStatus.VERIFIED);
    }

    @Test
    void check_valueDeviationBeyondTolerance_needVerify() {
        // Arrange：真值 30.55，模型 15.0 → 相对误差 ~51% > 5%
        when(financeAdapter.fetch(subject)).thenReturn(ok(Map.of("roe", new BigDecimal("30.55"))));
        BriefFact fact = new BriefFact("ROE 15%", "roe", 15.0, "FINANCE", "http://f");

        // Act
        HallucinationResult result = checker.check(List.of(fact), subject);

        // Assert
        assertThat(result.needVerify()).isTrue();
        FactCheck check = result.checks().get(0);
        assertThat(check.status()).isEqualTo(FactCheckStatus.NEED_VERIFY);
        assertThat(check.reason()).contains("数值不符");
    }

    @Test
    void check_sourceDegraded_needVerifySourceUnavailable() {
        // Arrange：财务源降级 MISSING
        when(financeAdapter.fetch(subject))
                .thenReturn(SourceResult.missing(SourceCode.FINANCE, 1L, "财务源"));

        BriefFact fact = new BriefFact("ROE 30%", "roe", 30.0, "FINANCE", "http://f");

        // Act
        HallucinationResult result = checker.check(List.of(fact), subject);

        // Assert：源暂不可用，区分于「数值不符」
        assertThat(result.needVerify()).isTrue();
        assertThat(result.checks().get(0).status()).isEqualTo(FactCheckStatus.NEED_VERIFY);
        assertThat(result.checks().get(0).reason()).contains("源降级");
    }

    @Test
    void check_fieldMissing_needVerifySourceUnavailable() {
        // Arrange：源 OK 但字段缺失（data 无 roe 键）
        when(financeAdapter.fetch(subject))
                .thenReturn(ok(Map.of("revenue", new BigDecimal("100"))));
        BriefFact fact = new BriefFact("ROE 30%", "roe", 30.0, "FINANCE", "http://f");

        // Act
        HallucinationResult result = checker.check(List.of(fact), subject);

        // Assert
        assertThat(result.needVerify()).isTrue();
        assertThat(result.checks().get(0).reason()).contains("字段缺失");
    }

    @Test
    void check_nullValue_skipped() {
        // Arrange：定性事实无数值
        when(financeAdapter.fetch(subject)).thenReturn(ok(Map.of("roe", new BigDecimal("30"))));
        BriefFact fact = new BriefFact("盈利能力强劲", "roe", null, "FINANCE", "http://f");

        // Act
        HallucinationResult result = checker.check(List.of(fact), subject);

        // Assert：无数值跳过，不触发 status=3
        assertThat(result.needVerify()).isFalse();
        assertThat(result.checks().get(0).status()).isEqualTo(FactCheckStatus.SKIPPED);
    }

    @Test
    void check_unmappedMetric_skipped() {
        // Arrange：net_profit_yoy 未映射真值源（adapter 不产 YoY）
        when(financeAdapter.fetch(subject)).thenReturn(ok(Map.of()));
        BriefFact fact = new BriefFact("净利同比+15%", "net_profit_yoy", 15.0, "FINANCE", "http://f");

        // Act
        HallucinationResult result = checker.check(List.of(fact), subject);

        // Assert：不在校验范围
        assertThat(result.needVerify()).isFalse();
        assertThat(result.checks().get(0).status()).isEqualTo(FactCheckStatus.SKIPPED);
    }

    @Test
    void check_fetchThrows_needVerify() {
        // Arrange：adapter 取数抛异常
        when(financeAdapter.fetch(subject)).thenThrow(new RuntimeException("连接超时"));
        BriefFact fact = new BriefFact("ROE 30%", "roe", 30.0, "FINANCE", "http://f");

        // Act
        HallucinationResult result = checker.check(List.of(fact), subject);

        // Assert：异常不阻断，标源暂不可用
        assertThat(result.needVerify()).isTrue();
        assertThat(result.checks().get(0).reason()).contains("取数异常");
    }

    @Test
    void check_emptyFacts_noNeedVerify() {
        // Act + Assert：空事实不校验，needVerify=false
        assertThat(checker.check(List.of(), subject).needVerify()).isFalse();
        assertThat(checker.check(null, subject).needVerify()).isFalse();
    }

    @Test
    void check_mixedFacts_anyNeedVerify_propagates() {
        // Arrange：一条吻合 + 一条不符
        when(financeAdapter.fetch(subject))
                .thenReturn(
                        ok(
                                Map.of(
                                        "roe",
                                        new BigDecimal("30.5"),
                                        "revenue",
                                        new BigDecimal("100"))));
        BriefFact ok = new BriefFact("ROE 30.5%", "roe", 30.5, "FINANCE", "http://f1");
        BriefFact bad = new BriefFact("营收 50", "revenue", 50.0, "FINANCE", "http://f2");

        // Act
        HallucinationResult result = checker.check(List.of(ok, bad), subject);

        // Assert：任一不符 → needVerify=true
        assertThat(result.needVerify()).isTrue();
        assertThat(result.checks()).hasSize(2);
    }

    private static SourceResult ok(Map<String, Object> data) {
        return SourceResult.ok(SourceCode.FINANCE, 1L, data, "财务源", Instant.now());
    }

    private static Subject subject() {
        return Subject.reconstruct(
                1L,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of("eastmoney_code", "600519"),
                "白酒",
                SubjectStatus.ENABLED,
                0L,
                Instant.now(),
                Instant.now());
    }
}
