package com.info.platform.infrastructure.ai;

import com.info.platform.domain.ai.LlmProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * GLM / 智谱 provider adapter（T19，fallback provider，Spike-2 §2.2 + §3）。
 *
 * <p>调 {@code https://open.bigmodel.cn/api/paas/v4/chat/completions}（OpenAI 兼容），模型 {@code
 * glm-4-flash-250414}（免费档，128K 上下文）。作 DeepSeek 失败时的降本兜底。 与 {@link DeepSeekAdapter} 共享 {@link
 * AbstractOpenAiCompatProvider}，差异仅在配置。
 *
 * <p>{@code response_format} 支持性 🟡 待实测（Spike-2 §5.2）；T19 mock 测试不依赖真实 API， 实测留 API key 后由 T21
 * 首个用例验证，不通过则启用 Spike-2 §5.3 提示词约束+解析兜底（届时改本类）。
 *
 * <p>未配置时 inert（同 {@link DeepSeekAdapter}）。
 */
@Component
public class GlmAdapter extends AbstractOpenAiCompatProvider {

    private static final String CONFIG_NAME = "glm";

    public GlmAdapter(RestClient.Builder restClientBuilder, LlmConfig config) {
        super(restClientBuilder, config.providerByName(CONFIG_NAME));
    }

    @Override
    public String name() {
        return CONFIG_NAME;
    }

    @Override
    protected LlmProvider provider() {
        return LlmProvider.GLM;
    }
}
