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
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.RuntimeLlmProvider;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * DeepSeekAdapter 单测（T19+T35）：OpenAI 兼容 chat/completions 请求构造与响应解析（Spike-2 §4.1）。
 *
 * <p>用 {@link MockRestServiceServer} 模拟 DeepSeek 响应（不依赖真实 API key）： 覆盖正常响应解析（content + usage +
 * provider + model）/
 * 请求体断言（response_format=json_object、model、messages、stream、max_tokens、Authorization Bearer）/ 模型覆盖 /
 * HTTP 500 抛 {@link RestClientResponseException}（供 gateway 切 fallback）/ 未配置抛 {@link
 * IllegalStateException}（inert）。
 *
 * <p>T35 追加：model/api-key <b>每次调用经 ConfigCenter 现读</b>（改模型/换 key 对下一次调用生效）、 baseUrl
 * 读<b>启动期冻结值</b>（RESTART 级，页面保存不热切端点）。
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
        DeepSeekAdapter boundAdapter = new DeepSeekAdapter(builder, configCenterWithDeepSeek());

        assertThatThrownBy(() -> boundAdapter.chat(request))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    void chat_notConfigured_throwsIllegalState() {
        ConfigCenter empty = ConfigCenterStubs.stubOf(); // 无任何 provider
        DeepSeekAdapter adapter = new DeepSeekAdapter(RestClient.builder(), empty);
        assertThatThrownBy(
                        () ->
                                adapter.chat(
                                        LlmRequest.json(
                                                List.of(new ChatMessage("user", "x")), "1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deepseek");
    }

    @Test
    void chat_modelAndKeyChangedAtRuntime_nextCallUsesNewValues() {
        // Arrange（T35 热改）：可变 provider 夹具承载；两次预期先注册（按序消费），两次调用之间改模型与 key
        LlmProviderFixtures.ProviderFixture provider = LlmProviderFixtures.deepseek();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepSeekAdapter adapter = new DeepSeekAdapter(builder, ConfigCenterStubs.stubOf(provider));

        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.model", is(MODEL)))
                .andExpect(
                        req ->
                                assertThat(req.getHeaders().getFirst("Authorization"))
                                        .isEqualTo("Bearer " + API_KEY))
                .andRespond(withSuccess(RESPONSE_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.model", is("deepseek-v4-pro")))
                .andExpect(
                        req ->
                                assertThat(req.getHeaders().getFirst("Authorization"))
                                        .isEqualTo("Bearer sk-rotated-key"))
                .andRespond(withSuccess(RESPONSE_JSON, MediaType.APPLICATION_JSON));
        LlmRequest request = LlmRequest.json(List.of(new ChatMessage("user", "ctx")), "1");

        // Act：第一次按当前模型/key 调用；随后页面改模型 + 换 key（保存即生效），第二次调用
        adapter.chat(request);
        provider.setModel("deepseek-v4-pro");
        provider.setApiKey("sk-rotated-key");
        LlmResponse resp = adapter.chat(request);

        // Assert：下一次调用即用新模型与新 key
        assertThat(resp.model()).isEqualTo("deepseek-v4-pro");
        server.verify();
    }

    @Test
    void chat_baseUrlAlwaysReadsBootSnapshot_evenIfRuntimeDocChanged() {
        // Arrange（RESTART 级）：运行时视图 baseUrl 已被页面改为新端点，但 boot 冻结值仍是旧端点
        RuntimeLlmProvider liveView =
                new RuntimeLlmProvider(
                        "deepseek",
                        MODEL,
                        true,
                        true,
                        null,
                        0,
                        0,
                        "https://runtime-changed.example.com",
                        "k",
                        null,
                        null);
        ConfigCenter configCenter = Mockito.mock(ConfigCenter.class);
        Mockito.when(configCenter.provider("deepseek")).thenReturn(Optional.of(liveView));
        Mockito.when(configCenter.bootLlmProviderBaseUrl("deepseek")).thenReturn(BASE_URL);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString(BASE_URL)))
                .andExpect(requestTo(containsString("/chat/completions")))
                .andRespond(withSuccess(RESPONSE_JSON, MediaType.APPLICATION_JSON));
        DeepSeekAdapter adapter = new DeepSeekAdapter(builder, configCenter);

        // Act + Assert：请求打到 boot 冻结端点（保存的 baseUrl 重启后才生效）
        LlmResponse resp =
                adapter.chat(LlmRequest.json(List.of(new ChatMessage("user", "ctx")), "1"));
        assertThat(resp.model()).isEqualTo(MODEL);
        server.verify();
    }

    /** 断言请求体与 Bearer 头（response_format=json_object 等，Spike-2 §4.1 契约）。 */
    private void assertDeepSeekRequestBody(MockRestServiceServer server, String expectedModel) {
        assertDeepSeekRequestBody(server, expectedModel, 8192);
    }

    private void assertDeepSeekRequestBody(
            MockRestServiceServer server, String expectedModel, int expectedMaxTokens) {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model", is(expectedModel)))
                .andExpect(jsonPath("$.response_format.type", is("json_object")))
                .andExpect(jsonPath("$.messages[0].role", is("system")))
                .andExpect(jsonPath("$.messages[1].role", is("user")))
                .andExpect(jsonPath("$.max_tokens", is(expectedMaxTokens)))
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
        DeepSeekAdapter adapter = new DeepSeekAdapter(builder, configCenterWithDeepSeek());
        setter.accept(server);
        LlmResponse resp = adapter.chat(request);
        server.verify();
        return resp;
    }

    static ConfigCenter configCenterWithDeepSeek() {
        return ConfigCenterStubs.stubOf(LlmProviderFixtures.deepseek());
    }
}
