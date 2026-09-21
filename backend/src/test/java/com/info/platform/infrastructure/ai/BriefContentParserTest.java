package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefFact;
import com.info.platform.domain.ai.BriefKeyEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BriefContentParser 单测（T21）：解析兜底链 + 序列化/回链采集，AAA 结构。
 *
 * <p>对齐 Spike-2 §5.3：正常 JSON / {@code ```json} 标记 / 空内容 / 非法 JSON / 多余字段容错 / 缺数组归空。 不依赖真实 LLM API
 * key——用构造的 JSON 串验证解析逻辑。
 */
class BriefContentParserTest {

    private BriefContentParser parser;

    private static final String VALID_JSON =
            "{\"summary\":\"摘要\",\"keyEvents\":[{\"event\":\"e\",\"impact\":\"利好\",\"reason\":\"r\","
                    + "\"sourceUrl\":\"http://a\"}],\"bias\":\"利好\",\"biasReason\":\"理由\","
                    + "\"watchSuggestion\":\"关注\",\"facts\":[{\"claim\":\"c\",\"metric\":\"roe\","
                    + "\"value\":30.5,\"source\":\"FINANCE\",\"sourceUrl\":\"http://f\"}],"
                    + "\"disclaimer\":\"AI 生成，非投资建议\"}";

    @BeforeEach
    void setUp() {
        parser = new BriefContentParser(new ObjectMapper());
    }

    @Test
    void parse_validJson_returnsBriefContent() {
        // Act
        Optional<BriefContent> opt = parser.parse(VALID_JSON);

        // Assert
        assertThat(opt).isPresent();
        BriefContent c = opt.get();
        assertThat(c.summary()).isEqualTo("摘要");
        assertThat(c.bias()).isEqualTo("利好");
        assertThat(c.facts()).hasSize(1);
        assertThat(c.facts().get(0).metric()).isEqualTo("roe");
        assertThat(c.facts().get(0).value()).isEqualTo(30.5);
        assertThat(c.keyEvents()).hasSize(1);
        assertThat(c.disclaimer()).isEqualTo("AI 生成，非投资建议");
    }

    @Test
    void parse_jsonFence_stripsAndParses() {
        // Arrange：```json 代码块包裹
        String fenced = "```json\n" + VALID_JSON + "\n```";

        // Act
        Optional<BriefContent> opt = parser.parse(fenced);

        // Assert
        assertThat(opt).isPresent();
        assertThat(opt.get().summary()).isEqualTo("摘要");
    }

    @Test
    void parse_surroundingProse_extractsBraceSpan() {
        // Arrange：前后有解释文字
        String withProse = "好的，这是简报：\n" + VALID_JSON + "\n以上为分析结果。";

        // Act
        Optional<BriefContent> opt = parser.parse(withProse);

        // Assert：取首 { 到末 } 之间
        assertThat(opt).isPresent();
        assertThat(opt.get().facts()).hasSize(1);
    }

    @Test
    void parse_blankContent_returnsEmpty() {
        // Act + Assert：空 content（JSON mode 可能空，§5.2）
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    void parse_illegalJson_returnsEmpty() {
        // Arrange
        String illegal = "{not a valid json";

        // Act + Assert
        assertThat(parser.parse(illegal)).isEmpty();
    }

    @Test
    void parse_extraFields_tolerated() {
        // Arrange：模型多输出 topRecommend（每日推荐 schema），FAIL_ON_UNKNOWN off
        String withExtra = VALID_JSON.replace("}", ",\"topRecommend\":[]}");

        // Act
        Optional<BriefContent> opt = parser.parse(withExtra);

        // Assert
        assertThat(opt).isPresent();
    }

    @Test
    void parse_missingArrays_normalizedToEmpty() {
        // Arrange：省略 keyEvents/facts 数组
        String noArrays = "{\"summary\":\"s\",\"bias\":\"中性\",\"disclaimer\":\"d\"}";

        // Act
        Optional<BriefContent> opt = parser.parse(noArrays);

        // Assert：紧凑构造器归空，不 NPE
        assertThat(opt).isPresent();
        assertThat(opt.get().keyEvents()).isEmpty();
        assertThat(opt.get().facts()).isEmpty();
    }

    @Test
    void writeJson_roundTripsBriefContent() {
        // Arrange
        BriefContent content = parser.parse(VALID_JSON).orElseThrow();

        // Act
        String json = parser.writeJson(content);

        // Assert：回写后再解析应等价
        BriefContent reparsed = parser.parse(json).orElseThrow();
        assertThat(reparsed.summary()).isEqualTo(content.summary());
        assertThat(reparsed.facts()).hasSize(content.facts().size());
    }

    @Test
    void writeSourceLinks_dedupesFromFactsAndKeyEvents() {
        // Arrange：facts 与 keyEvents 各有重复 url
        BriefContent content =
                new BriefContent(
                        "s",
                        List.of(
                                new BriefKeyEvent("e1", "利好", "r", "http://dup"),
                                new BriefKeyEvent("e2", "利空", "r", "http://ke2")),
                        "利好",
                        "r",
                        "w",
                        List.of(
                                new BriefFact("c1", "roe", 30.0, "FINANCE", "http://f1"),
                                new BriefFact("c2", "eps", 5.0, "FINANCE", "http://dup")),
                        "d");

        // Act
        String links = parser.writeSourceLinks(content);

        // Assert：去重，保序（先 facts 再 keyEvents）
        assertThat(parser.readSourceLinks(links))
                .containsExactly("http://f1", "http://dup", "http://ke2");
    }

    @Test
    void writeSourceLinks_noUrls_returnsEmptyArray() {
        // Arrange：无 url
        BriefContent content =
                new BriefContent(
                        "s",
                        List.of(),
                        "中性",
                        "r",
                        "w",
                        List.of(new BriefFact("c", "roe", 30.0, "FINANCE", null)),
                        "d");

        // Act
        String links = parser.writeSourceLinks(content);

        // Assert
        assertThat(links).isEqualTo("[]");
        assertThat(parser.readSourceLinks(links)).isEmpty();
    }

    @Test
    void readSourceLinks_nullOrBlank_returnsEmpty() {
        assertThat(parser.readSourceLinks(null)).isEmpty();
        assertThat(parser.readSourceLinks("")).isEmpty();
    }

    @Test
    void readSourceLinks_illegalJson_returnsEmpty() {
        // Arrange + Act + Assert：非法 JSON 数组 → 兜底空（不抛）
        assertThat(parser.readSourceLinks("not a json array")).isEmpty();
    }
}
