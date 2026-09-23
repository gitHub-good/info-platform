package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PromptVersionGenerator 单测（T45 / ADR-0021）：MINOR/MAJOR 两档、多版本取 max（v1.9 → v1.10 数值序）、 非法格式忽略、空集起步
 * v1.0、数值比较器。
 */
class PromptVersionGeneratorTest {

    @Test
    void nextVersion_minor_bumpsMinorOfMax() {
        assertThat(
                        PromptVersionGenerator.nextVersion(
                                List.of("v1.0"), PromptVersionGenerator.VersionStrategy.MINOR))
                .isEqualTo("v1.1");
        // v1.9 → v1.10（数值序正确，错序风险由 Java 排序消除）
        assertThat(
                        PromptVersionGenerator.nextVersion(
                                List.of("v1.9"), PromptVersionGenerator.VersionStrategy.MINOR))
                .isEqualTo("v1.10");
        // max 取 (major, minor) 全序最大：v2.0 > v1.10
        assertThat(
                        PromptVersionGenerator.nextVersion(
                                Arrays.asList("v1.0", "v1.9", "v1.10", "v2.0", "v0.3"),
                                PromptVersionGenerator.VersionStrategy.MINOR))
                .isEqualTo("v2.1");
    }

    @Test
    void nextVersion_major_bumpsMajorAndResetsMinor() {
        assertThat(
                        PromptVersionGenerator.nextVersion(
                                List.of("v1.9"), PromptVersionGenerator.VersionStrategy.MAJOR))
                .isEqualTo("v2.0");
        assertThat(
                        PromptVersionGenerator.nextVersion(
                                Arrays.asList("v1.9", "v2.0"),
                                PromptVersionGenerator.VersionStrategy.MAJOR))
                .isEqualTo("v3.0");
    }

    @Test
    void nextVersion_nullStrategy_defaultsToMinor() {
        assertThat(PromptVersionGenerator.nextVersion(List.of("v1.1"), null)).isEqualTo("v1.2");
    }

    @Test
    void nextVersion_emptyOrInvalidVersions_startsFromV1() {
        // 空集（防御：场景无任何版本行）
        assertThat(PromptVersionGenerator.nextVersion(Collections.emptyList(), null))
                .isEqualTo("v1.0");
        // 全非法格式（手工改库产物）→ 不参与 max，从 v1.0 起
        assertThat(PromptVersionGenerator.nextVersion(Arrays.asList("v1.0.1", "abc", ""), null))
                .isEqualTo("v1.0");
        // 非法格式与合法混存 → 仅合法参与 max
        assertThat(PromptVersionGenerator.nextVersion(Arrays.asList("v1.2", "v1.0.1"), null))
                .isEqualTo("v1.3");
    }

    @Test
    void nextVersion_nullCollectionOrNullElement_defensivelyStartsFromValidBase() {
        // null 集合（防御）按空集处理 → v1.0
        assertThat(PromptVersionGenerator.nextVersion(null, null)).isEqualTo("v1.0");
        // 清单含 null 元素（防御）忽略之，仅按合法行取 max
        assertThat(
                        PromptVersionGenerator.nextVersion(
                                Arrays.asList("v1.0", (String) null),
                                PromptVersionGenerator.VersionStrategy.MINOR))
                .isEqualTo("v1.1");
    }

    @Test
    void nextVersion_numericOverflowVersion_ignoredAsInvalid() {
        // 超长数字串（\d+ 可超 int 上限）→ parseInt 溢出防御分支：忽略不参与 max
        String overflow = "v" + "9".repeat(20) + ".0";
        assertThat(
                        PromptVersionGenerator.nextVersion(
                                Arrays.asList(overflow, "v1.2"),
                                PromptVersionGenerator.VersionStrategy.MINOR))
                .isEqualTo("v1.3");
        // 仅溢出行 → 全部忽略，从 v1.0 起
        assertThat(PromptVersionGenerator.nextVersion(List.of(overflow), null)).isEqualTo("v1.0");
    }

    @Test
    void compareNumeric_nullArguments_fallBackToStringOrderWithoutThrowing() {
        // null 入参防御：退化为 "null" 字符串比较，全序稳定不抛异常
        assertThat(PromptVersionGenerator.compareNumeric(null, "v1.0")).isNegative();
        assertThat(PromptVersionGenerator.compareNumeric("v1.0", null)).isPositive();
        assertThat(PromptVersionGenerator.compareNumeric(null, null)).isZero();
    }

    @Test
    void descending_ordersVersionsNumericallyDesc() {
        // 便捷比较器（versions 列表降序排序用，list() 同款用法）：数值序 v1.10 > v1.9 > v1.0（字典序会错序）
        List<String> versions = new java.util.ArrayList<>(List.of("v1.0", "v1.10", "v1.9"));
        versions.sort(PromptVersionGenerator.descending().reversed());
        assertThat(versions).containsExactly("v1.10", "v1.9", "v1.0");
    }

    @Test
    void compareNumeric_ordersByNumericNotLexicographic() {
        // 字典序陷阱：v1.10 < v1.9；数值序：v1.9 < v1.10
        assertThat(PromptVersionGenerator.compareNumeric("v1.10", "v1.9")).isPositive();
        assertThat(PromptVersionGenerator.compareNumeric("v1.9", "v1.10")).isNegative();
        assertThat(PromptVersionGenerator.compareNumeric("v2.0", "v1.99")).isPositive();
        assertThat(PromptVersionGenerator.compareNumeric("v1.0", "v1.0")).isZero();
    }

    @Test
    void compareNumeric_invalidFormat_fallsBackToStringCompare() {
        // 非法格式不抛异常，退化为字符串比较保证全序
        assertThat(PromptVersionGenerator.compareNumeric("abc", "v1.0")).isNegative();
        assertThat(PromptVersionGenerator.compareNumeric("v1.0", "abc")).isPositive();
    }
}
