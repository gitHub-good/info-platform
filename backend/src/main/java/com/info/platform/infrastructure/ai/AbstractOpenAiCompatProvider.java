package com.info.platform.infrastructure.ai;

import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.RuntimeLlmProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容 provider 公共基类（Spike-2 §4.3；T35 热改：构造期捕获配置 → 持 provider 名用时解析，ADR-0017 §4.3）。
 *
 * <p>DeepSeek 与 GLM 共享同一请求/响应结构（{@code messages}/{@code model}/{@code temperature}/ {@code
 * response_format}/{@code choices[0].message.content}/{@code usage}），差异仅在 base-url/model/api-key。
 * 本类封装 RestClient + Bearer 认证 + 请求构造 + 响应解析；子类只声明 {@link #name} 与 {@link #provider} 身份。
 *
 * <p><b>配置用时解析</b>（每次 chat 经 {@link ConfigCenter} 快照，页面保存即对下一次调用生效）： model 与 apiKey（DB 密文 &gt;
 * 环境变量）为 LIVE 级取<b>当前</b>值；baseUrl 为 RESTART 级读<b>启动期冻结快照</b> （{@link
 * ConfigCenter#bootLlmProviderBaseUrl}，页面明示重启后生效）。
 *
 * <p>调用契约：
 *
 * <ul>
 *   <li>POST {@code <base-url>/chat/completions}，body 含 {@code response_format={"type":...}}（JSON
 *       mode，Spike-2 §4.1）、{@code stream=false}、{@code max_tokens} 防截断。
 *   <li>HTTP 4xx/5xx 由 {@code .retrieve()} 抛 {@code RestClientResponseException}（含 429 限频）， 连接错误抛
 *       {@code ResourceAccessException}，均上抛由 {@link LlmGatewayImpl} 切 fallback； 调用级超时由 {@code
 *       LlmGatewayImpl} 以 Future 包装承担（对齐 {@code llm.timeout-seconds}，同 {@code ResilienceRunner}
 *       思路但不耦合 {@code SourceCode}）。
 *   <li>{@code content} 原样返回（可能空/非法，解析兜底属 T21）；{@code usage} 缺失时记 WARN 并置 0/0。
 * </ul>
 *
 * <p><b>Inert</b>：运行时无该 provider 配置或无 baseUrl（如测试上下文未配 {@code llm} 段），{@link #chat} 抛 {@code
 * IllegalStateException}，不触发真实 HTTP——保证无 key 的测试/启动上下文不 fail-fast、不误调用。
 */
public abstract class AbstractOpenAiCompatProvider implements LlmProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAiCompatProvider.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;
    private final ConfigCenter configCenter;

    protected AbstractOpenAiCompatProvider(
            RestClient.Builder restClientBuilder, ConfigCenter configCenter) {
        this.configCenter = configCenter;
        // 不在共享 builder 上设 baseUrl/默认头/请求工厂（避免多 adapter 互相污染、且不与 MockRestServiceServer
        // 的 requestFactory 冲突）；baseUrl 与 Bearer 头随请求带，超时由 LlmGatewayImpl 以 Future 包装承担。
        this.restClient = restClientBuilder == null ? null : restClientBuilder.build();
    }

    /** 本 adapter 厂商枚举（子类提供，用于回填 {@link LlmResponse#provider}）。 */
    protected abstract LlmProvider provider();

    @Override
    public final LlmResponse chat(LlmRequest request) {
        RuntimeLlmProvider cfg = configCenter.provider(name()).orElse(null);
        if (cfg == null || restClient == null) {
            throw new IllegalStateException("LLM provider " + name() + " 未配置，无法调用");
        }
        // baseUrl 为 RESTART 级：启动期冻结快照（保存后需重启才切端点）
        String baseUrl = configCenter.bootLlmProviderBaseUrl(name());
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("LLM provider " + name() + " 未配置 baseUrl，无法调用");
        }
        String model = resolveModel(request, cfg.model());
        Map<String, Object> body = buildBody(request, model);
        log.debug(
                "LLM 调用 provider={} model={} messages={} responseFormat={}",
                name(),
                model,
                request.messages().size(),
                request.responseFormatType());

        @SuppressWarnings("unchecked")
        Map<String, Object> resp =
                restClient
                        .post()
                        .uri(baseUrl + CHAT_COMPLETIONS_PATH)
                        .header("Authorization", "Bearer " + cfg.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);

        if (resp == null) {
            throw new IllegalStateException("LLM 响应体为空 provider=" + name());
        }
        return parse(resp, model);
    }

    private static String resolveModel(LlmRequest request, String configuredModel) {
        return (request.model() != null && !request.model().isBlank())
                ? request.model()
                : configuredModel;
    }

    private static Map<String, Object> buildBody(LlmRequest request, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", toMessageMaps(request.messages()));
        if (request.responseFormatType() != null && !request.responseFormatType().isBlank()) {
            body.put("response_format", Map.of("type", request.responseFormatType()));
        }
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", false);
        return body;
    }

    private static List<Map<String, String>> toMessageMaps(List<ChatMessage> messages) {
        return messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private LlmResponse parse(Map<String, Object> resp, String model) {
        Object choices = resp.get("choices");
        String content = null;
        if (choices instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            Object message = first.get("message");
            if (message instanceof Map<?, ?> msg) {
                Object c = msg.get("content");
                content = c == null ? null : String.valueOf(c);
            }
        }
        LlmUsage usage = extractUsage(resp.get("usage"));
        if (content == null) {
            log.warn(
                    "LLM 返回 content 为空 provider={}（JSON mode 有概率空 content，见 Spike-2 §5.2）", name());
        }
        return new LlmResponse(content, usage, provider(), model);
    }

    private static LlmUsage extractUsage(Object usageObj) {
        if (!(usageObj instanceof Map<?, ?> map)) {
            log.warn("LLM usage 字段缺失，按 0 计入预算");
            return new LlmUsage(0, 0);
        }
        int prompt = asInt(map.get("prompt_tokens"));
        int completion = asInt(map.get("completion_tokens"));
        return new LlmUsage(prompt, completion);
    }

    private static int asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
