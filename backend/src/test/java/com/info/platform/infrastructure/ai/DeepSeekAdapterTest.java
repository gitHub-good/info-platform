package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * DeepSeekAdapter 单测（T19）：OpenAI 兼容 chat/completions 请求构造与响应解析（Spike-2 §4.1）。
 *
 * <p>用 {@link MockRestServiceServer} 模拟 DeepSeek 响应（不依赖真实 API key）： 覆盖正常响应解析（content + usage +
 * provider + model）/
 * 请求体断言（response_format=json_object、model、messages、stream、max_tokens、Authorization Bearer）/ 模型覆盖 /
 * HTTP 500 抛 {@link RestClientResponseException}（供 gateway 切 fallback）/ 未配置抛 {@link
 * IllegalStateException}（inert）。
 *
 * <p>不设 HTTP 超时（MockRestServiceServer 即时响应）；调用级超时由 {@link LlmGatewayImpl} 以 Future 承担。
 */
class DeepSeekAdapterTest {

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String MODEL = "deepseek-flash";
    private static final String API_KEY = "test-key";

    private static final String RESPONSE_JSON =
            """
            {"choices":[{"message":{"role":"assistant","content":"hello brief"}}],\
            "usage":{"prompt_tokens":4100,"completion_tokens":820,"total_tokens":4920}}
            """;

    @Test
    void chat_normalResponse_parsesContentUsageProviderModel() {
        LlmRequest request =
                LlmRequest.json(
                        List.of(
                                new ChatMessage("system", "output json only"),
                                new ChatMessage("user", "context here")),
                        "1");

        LlmResponse resp =
                callWithMock(request, server -> assertDeepSeekRequestBody(server, MODEL));

        assertThat(resp.content()).isEqualTo("hello brief");
        assertThat(resp.usage().promptTokens()).isEqualTo(4100);
        assertThat(resp.usage().completionTokens()).isEqualTo(820);
        assertThat(resp.usage().totalTokens()).isEqualTo(4920);
        assertThat(resp.provider()).isEqualTo(LlmProvider.DEEPSEEK);
        assertThat(resp.model()).isEqualTo(MODEL);
    }

    @Test
    void chat_modelOverride_usedInRequestAndResponse() {
        LlmRequest request =
                new LlmRequest(
                        List.of(new ChatMessage("user", "ctx")),
                        "deepseek-v4-pro",
                        0.3,
                        2048,
                        LlmRequest.JSON_OBJECT,
                        "1");

        LlmResponse resp =
                callWithMock(
                        request,
                        server ->
                                server.expect(requestTo(containsString("/chat/completions")))
                                        .andExpect(jsonPath("$.model", is("deepseek-v4-pro")))
                                        .andRespond(
                                                withSuccess(
                                                        RESPONSE_JSON,
                                                        MediaType.APPLICATION_JSON)));

        assertThat(resp.model()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void chat_httpError_throwsForFallback() {
        LlmRequest request = LlmRequest.json(List.of(new ChatMessage("user", "ctx")), "1");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/chat/completions"))).andRespond(withServerError());
        DeepSeekAdapter boundAdapter = new DeepSeekAdapter(builder, configWithDeepSeek());

        assertThatThrownBy(() -> boundAdapter.chat(request))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    void chat_notConfigured_throwsIllegalState() {
        LlmConfig empty = new LlmConfig(); // 无 providers
        DeepSeekAdapter adapter = new DeepSeekAdapter(RestClient.builder(), empty);
        assertThatThrownBy(
                        () ->
                                adapter.chat(
                                        LlmRequest.json(
                                                List.of(new ChatMessage("user", "x")), "1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deepseek");
    }

    /** 断言请求体与 Bearer 头（response_format=json_object 等，Spike-2 §4.1 契约）。 */
    private void assertDeepSeekRequestBody(MockRestServiceServer server, String expectedModel) {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model", is(expectedModel)))
                .andExpect(jsonPath("$.response_format.type", is("json_object")))
                .andExpect(jsonPath("$.messages[0].role", is("system")))
                .andExpect(jsonPath("$.messages[1].role", is("user")))
                .andExpect(jsonPath("$.max_tokens", is(2048)))
                .andExpect(jsonPath("$.stream", is(false)))
                .andExpect(
                        req ->
                                assertThat(req.getHeaders().getFirst("Authorization"))
                                        .isEqualTo("Bearer " + API_KEY))
                .andRespond(withSuccess(RESPONSE_JSON, MediaType.APPLICATION_JSON));
    }

    private LlmResponse callWithMock(LlmRequest request, Consumer<MockRestServiceServer> setter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepSeekAdapter adapter = new DeepSeekAdapter(builder, configWithDeepSeek());
        setter.accept(server);
        LlmResponse resp = adapter.chat(request);
        server.verify();
        return resp;
    }

    static LlmConfig configWithDeepSeek() {
        return LlmConfigTest.configWith(LlmConfigTest.deepseekProvider());
    }
}
