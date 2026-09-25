package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * NewsAnalysis 实体单测（T120/T121）：L0 建行守卫（NEAR_DUP 必带主条引用）、L1 单向状态机（DONE 后拒绝重复归类）、 失败记账推进、 reconstruct
 * 回读、枚举白名单 fromName。AAA 结构，纯 JDK 零依赖。
 */
class NewsAnalysisTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    void newForL0_nearDupRequiresMainRef() {
        assertThatThrownBy(() -> NewsAnalysis.newForL0(1L, L0Result.NEAR_DUP, null, "hamming=2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewsAnalysis.newForL0(1L, L0Result.NEAR_DUP, 0L, "hamming=2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewsAnalysis.newForL0(1L, L0Result.PASS, 9L, null))
                .isInstanceOf(IllegalArgumentException.class); // 仅 NEAR_DUP 可带引用
        assertThatThrownBy(() -> NewsAnalysis.newForL0(1L, L0Result.NOISE, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);

        NewsAnalysis nearDup =
                NewsAnalysis.newForL0(2L, L0Result.NEAR_DUP, 1L, "hamming=2;lev=0.10");
        assertThat(nearDup.getNearDupOf()).isEqualTo(1L);
        assertThat(nearDup.getL0Detail()).isEqualTo("hamming=2;lev=0.10");
    }

    @Test
    void newForL0_initialStates() {
        NewsAnalysis row = NewsAnalysis.newForL0(1L, L0Result.PASS, null, null);

        assertThat(row.getL1Status()).isEqualTo(L1Status.PENDING);
        assertThat(row.getL2Status()).isEqualTo(L2Status.SKIP);
        assertThat(row.getL1Attempts()).isZero();
        assertThat(row.getImportanceScore()).isZero();
        assertThat(row.getMainCategory()).isNull();
        assertThat(row.isLowConfidence()).isFalse();
    }

    @Test
    void applyL1Result_transitionsPendingToDone() {
        NewsAnalysis row = NewsAnalysis.newForL0(1L, L0Result.PASS, null, null);

        row.applyL1Result("银行", null, null, 0.9, false, "[]", "v1.0", NOW);

        assertThat(row.getL1Status()).isEqualTo(L1Status.DONE);
        assertThat(row.getMainCategory()).isEqualTo("银行");
        assertThat(row.getConfidence()).isEqualTo(0.9);
        assertThat(row.getClassifiedAt()).isEqualTo(NOW);
        assertThat(row.getL1PromptVersion()).isEqualTo("v1.0");

        // DONE 后重复归类拒绝（幂等白名单——持久化侧另有条件 UPDATE 双保险）
        assertThatThrownBy(() -> row.applyL1Result("电子", null, null, 0.8, false, "[]", "v1.0", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyL1Result_failedRowCanReclassify_nextDayBackfill() {
        // FAILED 行重试语义（24h 补跑窗口内再进批 → DONE）
        NewsAnalysis row = NewsAnalysis.newForL0(1L, L0Result.PASS, null, null);
        row.markL1Failed();

        assertThatCode(() -> row.applyL1Result("银行", "社会服务", null, 0.3, true, null, "v1.0", NOW))
                .doesNotThrowAnyException();
        assertThat(row.getL1Status()).isEqualTo(L1Status.DONE);
        assertThat(row.getRawMain()).isEqualTo("社会服务");
        assertThat(row.isLowConfidence()).isTrue();
    }

    @Test
    void markL1Failed_incrementsAttempts() {
        NewsAnalysis row = NewsAnalysis.newForL0(1L, L0Result.PASS, null, null);

        row.markL1Failed();
        row.markL1Failed();

        assertThat(row.getL1Status()).isEqualTo(L1Status.FAILED);
        assertThat(row.getL1Attempts()).isEqualTo(2);
    }

    @Test
    void reconstruct_roundTripsAllFields() {
        NewsAnalysis row =
                NewsAnalysis.reconstruct(
                        7L,
                        100L,
                        L0Result.NEAR_DUP,
                        99L,
                        "hamming=3;lev=0.20",
                        4.5,
                        L1Status.DONE,
                        "银行",
                        "社会服务",
                        "食品饮料",
                        0.42,
                        true,
                        "[{\"code\":\"SH600519\"}]",
                        2,
                        "v1.0",
                        NOW,
                        L2Status.SKIP,
                        0,
                        NOW.minusSeconds(3600),
                        NOW.minusSeconds(60));

        assertThat(row.getId()).isEqualTo(7L);
        assertThat(row.getNewsId()).isEqualTo(100L);
        assertThat(row.getL0Result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(row.getNearDupOf()).isEqualTo(99L);
        assertThat(row.getImportanceScore()).isEqualTo(4.5);
        assertThat(row.getL1Status()).isEqualTo(L1Status.DONE);
        assertThat(row.getSubIndustry()).isEqualTo("食品饮料");
        assertThat(row.getMatchedSubjects()).isEqualTo("[{\"code\":\"SH600519\"}]");
        assertThat(row.getL2Status()).isEqualTo(L2Status.SKIP);
        assertThat(row.getCreatedAt()).isEqualTo(NOW.minusSeconds(3600));
    }

    @Test
    void enums_fromNameWhitelist() {
        // 迁移合法值白名单校验（方案库 10「状态机」）：未知值拒绝
        assertThat(L0Result.fromName("PASS")).isEqualTo(L0Result.PASS);
        assertThat(L1Status.fromName("DONE")).isEqualTo(L1Status.DONE);
        assertThat(L2Status.fromName("DEFERRED")).isEqualTo(L2Status.DEFERRED);
        assertThatThrownBy(() -> L0Result.fromName("GARBAGE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> L0Result.fromName(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> L1Status.fromName("XX"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> L2Status.fromName("XX"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
