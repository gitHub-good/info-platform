package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.markettop.Citation;
import com.info.platform.domain.markettop.DeepDiveOutputParser;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * DeepDiveOutputParserImpl 单测（M21 T182，方案 §4.4.4 步 1）：fence 容错 / 首{末}截取 / citations 宽容读取（缺
 * id/非文本跳过）/ 坏 JSON 与空输入 → empty。
 */
class DeepDiveOutputParserImplTest {

    private final DeepDiveOutputParser parser = new DeepDiveOutputParserImpl(new ObjectMapper());

    @Test
    void parse_plainJson_fullStructure() {
        Optional<Parsed> parsed =
                parser.parse(
                        """
                        {"thesis":"论点","highlights":[
                          {"text":"亮点一","citations":[{"type":"EVENT","id":1}]},
                          {"text":"亮点二","citations":[{"type":"NEWS","id":2}]}],
                         "risks":[
                          {"text":"风险一","citations":[{"type":"EVENT","id":1}]},
                          {"text":"风险二","citations":[]}],
                         "dataNotes":["口径注记"]}
                        """);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().thesis()).isEqualTo("论点");
        assertThat(parsed.get().highlights()).hasSize(2);
        assertThat(parsed.get().highlights().get(0).citations())
                .containsExactly(new Citation("EVENT", 1));
        assertThat(parsed.get().risks().get(1).citations()).isEmpty();
        assertThat(parsed.get().dataNotes()).containsExactly("口径注记");
    }

    @Test
    void parse_fencedAndDecoratedJson_tolerated() {
        Optional<Parsed> parsed =
                parser.parse("```json\n{\"thesis\":\"论点\",\"highlights\":[],\"risks\":[]}\n```");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().thesis()).isEqualTo("论点");
    }

    @Test
    void parse_invalidCitationEntries_skippedNotFatal() {
        Optional<Parsed> parsed =
                parser.parse(
                        """
                        {"thesis":"论点","highlights":[
                          {"text":"亮点","citations":[{"type":"EVENT"},{"id":5},{"type":"NEWS","id":"abc"}]}],
                         "risks":[]}
                        """);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().highlights().get(0).citations()).isEmpty();
    }

    @Test
    void parse_badJsonOrBlank_empty() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse("完全不是 JSON")).isEmpty();
        assertThat(parser.parse("{\"thesis\": 论点缺引号}")).isEmpty();
    }
}
