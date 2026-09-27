package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.CitationReconciler.Result;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * CitationReconciler 单测（M21 T182，方案 §4.4.5「引用与库对账」——零幻觉断言的机制化防线）： 结构化 (type,id) 集合比对——无效引用剔除 / 0
 * 有效引用条目剔除 / 条目数跌破下限整体拒 / 白名单 EVENT·NEWS 分型 / 合法输出零改动。
 */
class CitationReconcilerTest {

    private static Entry entry(String text, Citation... citations) {
        return new Entry(text, List.of(citations));
    }

    private static final Citation EVENT_1 = new Citation("EVENT", 1);

    private static final Citation EVENT_2 = new Citation("EVENT", 2);

    private static final Citation NEWS_3 = new Citation("NEWS", 3);

    private static final Set<Citation> WHITELIST = Set.of(EVENT_1, EVENT_2, NEWS_3);

    @Test
    void reconcile_validOutput_unchanged() {
        // Arrange：全部引用 ∈ 白名单
        Parsed parsed =
                new Parsed(
                        "论点",
                        List.of(entry("亮点一", EVENT_1), entry("亮点二", NEWS_3)),
                        List.of(entry("风险一", EVENT_2), entry("风险二", EVENT_1)),
                        List.of());

        // Act
        Result result = CitationReconciler.reconcile(parsed, WHITELIST);

        // Assert：零幻觉——原样通过
        assertThat(result.valid()).isTrue();
        assertThat(result.invalidCitations()).isZero();
        assertThat(result.droppedEntries()).isZero();
        assertThat(result.reconciled().highlights()).isEqualTo(parsed.highlights());
        assertThat(result.reconciled().risks()).isEqualTo(parsed.risks());
    }

    @Test
    void reconcile_fabricatedCitation_droppedAndEntrySurvivesWithValidSibling() {
        // Arrange：条目混入虚构引用（EVENT 999 ∉ 白名单）但同条目另有合法引用
        Citation fabricated = new Citation("EVENT", 999);
        Parsed parsed =
                new Parsed(
                        "论点",
                        List.of(entry("亮点一", fabricated, EVENT_1), entry("亮点二", NEWS_3)),
                        List.of(entry("风险一", EVENT_2), entry("风险二", EVENT_1)),
                        List.of());

        // Act
        Result result = CitationReconciler.reconcile(parsed, WHITELIST);

        // Assert：虚构引用剔除、条目因合法兄弟引用保留、留痕计数 1
        assertThat(result.valid()).isTrue();
        assertThat(result.invalidCitations()).isEqualTo(1);
        assertThat(result.droppedEntries()).isZero();
        assertThat(result.reconciled().highlights().get(0).citations()).containsExactly(EVENT_1);
    }

    @Test
    void reconcile_entryWithOnlyInvalidCitations_entryDropped_belowLimitRejected() {
        // Arrange：亮点一仅含虚构引用 → 条目剔除 → highlights 跌破下限 2
        Citation fabricatedNews = new Citation("NEWS", 404);
        Parsed parsed =
                new Parsed(
                        "论点",
                        List.of(entry("亮点一", fabricatedNews), entry("亮点二", NEWS_3)),
                        List.of(entry("风险一", EVENT_2), entry("风险二", EVENT_1)),
                        List.of());

        // Act
        Result result = CitationReconciler.reconcile(parsed, WHITELIST);

        // Assert：拒 → 调用方切模板兜底（§4.4.4 步 3 失败处置）
        assertThat(result.valid()).isFalse();
        assertThat(result.invalidCitations()).isEqualTo(1);
        assertThat(result.droppedEntries()).isEqualTo(1);
    }

    @Test
    void reconcile_entryWithoutCitations_entryDropped() {
        // Arrange：条目零引用（提示词要求每条 ≥1——缺引用视同无效）
        Parsed parsed =
                new Parsed(
                        "论点",
                        List.of(entry("亮点一"), entry("亮点二", NEWS_3)),
                        List.of(entry("风险一", EVENT_2), entry("风险二", EVENT_1)),
                        List.of());

        // Act
        Result result = CitationReconciler.reconcile(parsed, WHITELIST);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.droppedEntries()).isEqualTo(1);
    }

    @Test
    void reconcile_typeMismatch_sameIdDifferentTypeIsInvalid() {
        // Arrange：EVENT 3 ≠ NEWS 3——分型集合比对，同 id 不同 type 判无效
        Citation wrongType = new Citation("EVENT", 3);
        Parsed parsed =
                new Parsed(
                        "论点",
                        List.of(entry("亮点一", wrongType, EVENT_1), entry("亮点二", NEWS_3)),
                        List.of(entry("风险一", EVENT_2), entry("风险二", EVENT_1)),
                        List.of());

        // Act
        Result result = CitationReconciler.reconcile(parsed, WHITELIST);

        // Assert
        assertThat(result.valid()).isTrue();
        assertThat(result.invalidCitations()).isEqualTo(1);
        assertThat(result.reconciled().highlights().get(0).citations()).containsExactly(EVENT_1);
    }

    @Test
    void reconcile_duplicateValidCitations_dedupKept() {
        // Arrange：同条目重复合法引用
        Parsed parsed =
                new Parsed(
                        "论点",
                        List.of(entry("亮点一", EVENT_1, EVENT_1), entry("亮点二", NEWS_3)),
                        List.of(entry("风险一", EVENT_2), entry("风险二", EVENT_1)),
                        List.of());

        // Act
        Result result = CitationReconciler.reconcile(parsed, WHITELIST);

        // Assert：去重保序
        assertThat(result.valid()).isTrue();
        assertThat(result.reconciled().highlights().get(0).citations()).containsExactly(EVENT_1);
    }

    @Test
    void reconcile_emptyWhitelist_allEntriesDropped_rejected() {
        // Arrange：输入白名单为空（极端无事件无资讯）——一切引用皆虚构
        Parsed parsed =
                new Parsed(
                        "论点",
                        List.of(entry("亮点一", EVENT_1), entry("亮点二", EVENT_2)),
                        List.of(entry("风险一", EVENT_1), entry("风险二", EVENT_2)),
                        List.of());

        // Act
        Result result = CitationReconciler.reconcile(parsed, Set.of());

        // Assert：整体拒 → 模板兜底
        assertThat(result.valid()).isFalse();
        assertThat(result.droppedEntries()).isEqualTo(4);
        assertThat(result.invalidCitations()).isEqualTo(4);
    }
}
