package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Double 字段宽松反序列化回归（2026-09-25 真实事故：模型输出 "68.7890335262%" 致整份简报解析失败）。 */
class BriefContentParserLenientTest {

    private final BriefContentParser parser = new BriefContentParser(new ObjectMapper());

    @Test
    void parse_percentStringFactValue_toleratedAsNumber() {
        String raw =
                "{\"summary\":\"s\",\"facts\":[{\"claim\":\"毛利率约 68.8%\",\"metric\":\"gross_margin\","
                        + "\"value\":\"68.7890335262%\",\"source\":\"财务源\",\"sourceUrl\":\"u\"}],"
                        + "\"keyEvents\":[],\"tendency\":\"中性\",\"reasons\":[],\"risks\":[],"
                        + "\"disclaimer\":\"d\"}";
        var parsed = parser.parse(raw);
        assertThat(parsed).as("带 % 的数值串应宽松解析为 Double 而非整份失败").isPresent();
        assertThat(parsed.get().facts().get(0).value()).isEqualTo(68.7890335262);
    }

    @Test
    void parse_thousandSeparatorAndNumberForms_tolerated() {
        String raw =
                "{\"summary\":\"s\",\"facts\":[{\"claim\":\"营收\",\"metric\":\"revenue\","
                        + "\"value\":\"1,234.56\",\"source\":\"财务源\",\"sourceUrl\":null},"
                        + "{\"claim\":\"ROE\",\"metric\":\"roe\",\"value\":9.1,\"source\":\"财务源\",\"sourceUrl\":null},"
                        + "{\"claim\":\"定性\",\"metric\":\"other\",\"value\":\"不适用\",\"source\":\"x\",\"sourceUrl\":null}],"
                        + "\"keyEvents\":[],\"tendency\":\"中性\",\"reasons\":[],\"risks\":[],\"disclaimer\":\"d\"}";
        var parsed = parser.parse(raw);
        assertThat(parsed).isPresent();
        var facts = parsed.get().facts();
        assertThat(facts.get(0).value()).isEqualTo(1234.56);
        assertThat(facts.get(1).value()).isEqualTo(9.1);
        assertThat(facts.get(2).value()).as("非数值字符串置 null（字段可空语义），不阻断整份简报").isNull();
    }
}
