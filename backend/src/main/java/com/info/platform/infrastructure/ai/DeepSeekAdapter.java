package com.info.platform.infrastructure.ai;

import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.infrastructure.common.ConfigCenter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek provider adapter（T19 默认 provider，Spike-2 §2.1 + §3；T35 改运行时解析）。
 *
 * <p>调 {@code https://api.deepseek.com/chat/completions}（OpenAI 兼容），模型 {@code deepseek-flash}（性价比档，
 * JSON mode 稳、1M 上下文）。与 {@link GlmAdapter} 共享 {@link AbstractOpenAiCompatProvider}，差异仅在身份；
 * model/api-key/baseUrl 每次调用经 {@link ConfigCenter} 解析当前值（页面保存即生效，baseUrl 重启生效）。 无配置时 inert，{@link
 * #chat} 抛 {@code IllegalStateException} 不误调用。
 */
@Component
public class DeepSeekAdapter extends AbstractOpenAiCompatProvider {

    private static final String CONFIG_NAME = "deepseek";

    public DeepSeekAdapter(RestClient.Builder restClientBuilder, ConfigCenter configCenter) {
        super(restClientBuilder, configCenter);
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
