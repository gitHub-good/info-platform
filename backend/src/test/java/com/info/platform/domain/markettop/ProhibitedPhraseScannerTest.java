package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import com.info.platform.domain.markettop.ProhibitedPhraseScanner.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ProhibitedPhraseScanner 单测（M21 T182，方案 §4.4.4 步 4 / §4.4.6 黑名单正则）： thesis 命中整体拒 / 条目命中剔除该条目 /
 * 剔除后跌破下限整体拒 / 干净输出零改动 / 词表覆盖（买入/卖出/必涨/目标价等）。
 */
class ProhibitedPhraseScannerTest {

    private static Entry entry(String text) {
        return new Entry(text, List.of(new Citation("EVENT", 1)));
    }

    private static Parsed parsed(String thesis, String h1, String h2, String r1, String r2) {
        return new Parsed(
                thesis, List.of(entry(h1), entry(h2)), List.of(entry(r1), entry(r2)), List.of());
    }

    @Test
    void scan_cleanOutput_validUnchanged() {
        Parsed parsed = parsed("论点中性", "订单增长", "研发突破", "竞争加剧", "估值偏高");

        Result result = ProhibitedPhraseScanner.scan(parsed);

        assertThat(result.valid()).isTrue();
        assertThat(result.hits()).isEmpty();
        assertThat(result.cleaned().highlights()).hasSize(2);
        assertThat(result.cleaned().risks()).hasSize(2);
    }

    @Test
    void scan_thesisHit_wholeOutputRejected() {
        // Arrange：thesis 出现「建议买入」——整体兜底（不剔除 thesis 保留其余）
        Parsed parsed = parsed("基本面强劲，建议买入", "订单增长", "研发突破", "竞争加剧", "估值偏高");

        Result result = ProhibitedPhraseScanner.scan(parsed);

        assertThat(result.valid()).isFalse();
        assertThat(result.hits()).contains("买入");
    }

    @Test
    void scan_entryHit_entryRemoved_stillAboveLimit() {
        // Arrange：3 条亮点中 1 条命中「目标价」→ 剔除后剩 2 条恰达下限
        Parsed parsed =
                new Parsed(
                        "论点中性",
                        List.of(entry("机构给出目标价 15 元"), entry("研发突破"), entry("订单增长")),
                        List.of(entry("竞争加剧"), entry("估值偏高")),
                        List.of());

        Result result = ProhibitedPhraseScanner.scan(parsed);

        assertThat(result.valid()).isTrue();
        assertThat(result.hits()).contains("目标价");
        assertThat(result.cleaned().highlights()).hasSize(2);
        assertThat(result.cleaned().highlights())
                .extracting(Entry::text)
                .containsExactly("研发突破", "订单增长");
        assertThat(result.cleaned().risks()).hasSize(2);
    }

    @Test
    void scan_entryHitsBelowLimit_rejected() {
        // Arrange：两条亮点均命中（「必涨」「稳赚不赔」）→ highlights 跌破 2 → 整体拒
        Parsed parsed = parsed("论点中性", "业绩必涨", "稳赚不赔", "竞争加剧", "估值偏高");

        Result result = ProhibitedPhraseScanner.scan(parsed);

        assertThat(result.valid()).isFalse();
        assertThat(result.hits()).contains("必涨", "稳赚");
    }

    @Test
    void scan_blacklistCoversCorePhrases() {
        // 词表面回归（方案 §4.4.6 清单）：命中返回首个匹配词
        assertThat(ProhibitedPhraseScanner.firstHit("建议卖出获利了结")).isEqualTo("卖出");
        assertThat(ProhibitedPhraseScanner.firstHit("保证年化收益翻倍")).isEqualTo("保证年化收益");
        assertThat(ProhibitedPhraseScanner.firstHit("可以梭哈满仓干")).isEqualTo("梭哈");
        assertThat(ProhibitedPhraseScanner.firstHit("强烈推荐十倍股")).isEqualTo("强烈推荐");
        assertThat(ProhibitedPhraseScanner.firstHit("正常表述不含违禁词")).isNull();
    }
}
