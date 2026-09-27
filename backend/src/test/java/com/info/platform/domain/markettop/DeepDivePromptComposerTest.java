package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.DeepDiveInput.EventFact;
import com.info.platform.domain.markettop.DeepDiveInput.FactorDim;
import com.info.platform.domain.markettop.DeepDiveInput.NewsFact;
import com.info.platform.domain.markettop.DeepDiveInput.SubjectRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DeepDivePromptComposer 单测（M21 T182，方案 §4.4.2 输入契约）：9 占位符全注入（模板渲染不残留 {{key}}）+ 引用白名单 =
 * EVENT(topEvents) ∪ NEWS(relatedNews ∪ industryNews)——对账唯一合法集的单源派生。
 */
class DeepDivePromptComposerTest {

    private static final DeepDiveInput INPUT =
            new DeepDiveInput(
                    new SubjectRef("SZ300024", "机器人", "机械设备"),
                    List.of(
                            new FactorDim("catalyst", "事件催化", 81.2, 0.40),
                            new FactorDim("conduction", "行业传导", 29.4, 0.20),
                            new FactorDim("fundamental", "基本面边际", 55.1, 0.20),
                            new FactorDim("risk", "风险安全", 70.0, 0.20),
                            new FactorDim("valuation", "估值水平", 50.0, 0.00)),
                    58.4,
                    99,
                    true,
                    List.of(
                            new EventFact(123, "签订重大合同", "BULLISH", "HIGH", "2026-09-18", 0.75),
                            new EventFact(456, "监管问询", "BEARISH", "MEDIUM", "2026-09-21", 0.35)),
                    List.of(new NewsFact(789, "机器人产业政策出台", "2026-09-20T08:00:00Z", "财联社")),
                    List.of(new NewsFact(1011, "机械设备行业景气上行", "2026-09-19T02:00:00Z", null)),
                    marketSnapshot(),
                    10,
                    5,
                    3);

    private static Map<String, Double> marketSnapshot() {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        snapshot.put("close", 12.34);
        snapshot.put("pctChange", 2.1);
        snapshot.put("pe", 45.6);
        return snapshot;
    }

    @Test
    void placeholders_nineKeysAllRendered() {
        Map<String, String> placeholders = DeepDivePromptComposer.placeholders(INPUT);

        assertThat(placeholders)
                .containsOnlyKeys(
                        "subject",
                        "factors",
                        "totalScore",
                        "percentile",
                        "breakthrough",
                        "topEvents",
                        "relatedNews",
                        "industryNews",
                        "marketSnapshot");
        assertThat(placeholders.get("subject")).contains("SZ300024").contains("机器人");
        assertThat(placeholders.get("topEvents"))
                .contains("\"eventId\":123")
                .contains("\"eventId\":456");
        assertThat(placeholders.get("relatedNews")).contains("\"newsId\":789");
        assertThat(placeholders.get("industryNews")).contains("\"newsId\":1011");
        // 行情快照缺数键省略（pb 缺 → 不出现）
        assertThat(placeholders.get("marketSnapshot")).contains("close").doesNotContain("pb");
    }

    @Test
    void placeholders_emptyCollections_renderHonestEmpty() {
        DeepDiveInput emptyInput =
                new DeepDiveInput(
                        new SubjectRef("SH600000", "浦发银行", null),
                        List.of(),
                        30.0,
                        50,
                        false,
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        10,
                        0,
                        0);

        Map<String, String> placeholders = DeepDivePromptComposer.placeholders(emptyInput);

        assertThat(placeholders.get("topEvents")).isEqualTo("[]");
        assertThat(placeholders.get("relatedNews")).isEqualTo("[]");
        assertThat(placeholders.get("marketSnapshot")).isEqualTo("{}");
    }

    @Test
    void citationWhitelist_eventAndNewsUnion() {
        var whitelist = INPUT.citationWhitelist();

        // Arrange 白名单源：EVENT {123,456} ∪ NEWS {789,1011}
        assertThat(whitelist)
                .containsExactlyInAnyOrder(
                        new Citation("EVENT", 123),
                        new Citation("EVENT", 456),
                        new Citation("NEWS", 789),
                        new Citation("NEWS", 1011));
    }
}
