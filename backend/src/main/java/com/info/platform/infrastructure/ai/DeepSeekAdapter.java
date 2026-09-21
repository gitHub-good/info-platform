package com.info.platform.infrastructure.ai;

import com.info.platform.domain.ai.LlmProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek provider adapter（T19，默认 provider，Spike-2 §2.1 + §3）。
 *
 * <p>调 {@code https://api.deepseek.com/chat/completions}（OpenAI 兼容），模型 {@code deepseek-flash}（性价比档，
 * JSON mode 稳、1M 上下文）。与 {@link GlmAdapter} 共享 {@link AbstractOpenAiCompatProvider}，差异仅在配置。
 *
 * <p>从 {@link LlmConfig} 按配置键 {@code "deepseek"} 取本 provider 配置；若未配置（如测试上下文无 {@code llm} 段）则
 * inert，{@link #chat} 抛 {@link IllegalStateException} 不误调用。
 */
@Component
public class DeepSeekAdapter extends AbstractOpenAiCompatProvider {

    private static final String CONFIG_NAME = "deepseek";

    public DeepSeekAdapter(RestClient.Builder restClientBuilder, LlmConfig config) {
        super(restClientBuilder, config.providerByName(CONFIG_NAME));
    }

    @Override
    public String name() {
        return CONFIG_NAME;
    }

    @Override
    protected LlmProvider provider() {
        return LlmProvider.DEEPSEEK;
    }
}
