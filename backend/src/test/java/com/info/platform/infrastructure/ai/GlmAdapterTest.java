package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.infrastructure.common.ConfigCenter;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * GlmAdapter 单测（T19+T35）：GLM/智谱 OpenAI 兼容请求/响应（Spike-2 §4.2）。
 *
 * <p>与 {@link DeepSeekAdapterTest} 同构（共享 {@link AbstractOpenAiCompatProvider}），差异仅在 provider
 * 枚举、model 与 base-url。覆盖正常响应解析 + 请求体（base-url
 * 指向智谱、model=glm-4-flash-250414、response_format=json_object）+ 未配置 inert。
 *
 * <p>注：GLM {@code response_format} 支持性 🟡 待实测（Spike-2 §5.2）；mock 测试不依赖真实 API，实测留 API key 后由 T21
 * 首个用例验证。
 */
class GlmAdapterTest {

    private static final String MODEL = "glm-4-flash-250414";

    private static final String RESPONSE_JSON =
            """
            {"choices":[{"message":{"role":"assistant","content":"brief glm"}}],\
            "usage":{"prompt_tokens":4100,"completion_tokens":820,"total_tokens":4920}}
            """;

    @Test
    void chat_normalResponse_parsesAndTargetsGlmEndpoint() {
        LlmRequest request =
                LlmRequest.json(
                        List.of(
                                new ChatMessage("system", "output json"),
                                new ChatMessage("user", "ctx")),
                        "1");

        LlmResponse resp = callWithMock(request, this::assertGlmRequest);

        assertThat(resp.content()).isEqualTo("brief glm");
        assertThat(resp.usage().totalTokens()).isEqualTo(4920);
        assertThat(resp.provider()).isEqualTo(LlmProvider.GLM);
        assertThat(resp.model()).isEqualTo(MODEL);
    }

    private void assertGlmRequest(MockRestServiceServer server) {
        server.expect(requestTo(containsString("open.bigmodel.cn")))
                .andExpect(requestTo(containsString("/chat/completions")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model", is(MODEL)))
                .andExpect(jsonPath("$.response_format.type", is("json_object")))
                .andExpect(jsonPath("$.stream", is(false)))
                .andRespond(withSuccess(RESPONSE_JSON, MediaType.APPLICATION_JSON));
    }

    @Test
    void chat_notConfigured_throwsIllegalState() {
        ConfigCenter empty = ConfigCenterStubs.stubOf(); // 无任何 provider
        GlmAdapter adapter = new GlmAdapter(RestClient.builder(), empty);
        assertThatThrownBy(
                        () ->
                                adapter.chat(
                                        LlmRequest.json(
                                                List.of(new ChatMessage("user", "x")), "1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("glm");
    }

    private LlmResponse callWithMock(LlmRequest request, Consumer<MockRestServiceServer> setter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GlmAdapter adapter =
                new GlmAdapter(builder, ConfigCenterStubs.stubOf(LlmProviderFixtures.glm()));
        setter.accept(server);
        LlmResponse resp = adapter.chat(request);
        server.verify();
        return resp;
    }
}
