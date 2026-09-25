package com.info.platform.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link LlmRequest#json} 工厂默认参数防漂移（maxTokens 8192：2026-09-25 截断事故上调，防回退）。 */
class LlmRequestTest {

    @Test
    void jsonFactory_defaults_jsonModeAndRaisedMaxTokens() {
        LlmRequest request =
                LlmRequest.json(List.of(new ChatMessage("system", "输出 json")), "brief:stock");
        assertThat(request.maxTokens())
                .as("M12 扩容后简报输出实测顶格 2048 截断（llm_call_log output=2048 实证），上限 8192 防解析失败")
                .isEqualTo(8192);
        assertThat(request.responseFormatType()).isEqualTo("json_object");
        assertThat(request.temperature()).isEqualTo(0.3);
    }
}
