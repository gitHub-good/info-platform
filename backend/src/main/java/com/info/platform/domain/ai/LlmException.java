package com.info.platform.domain.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用失败异常：fallback 链上所有 provider 均失败时抛出（基础设施层 {@code LlmGatewayImpl} 抛、应用层 T21 捕获置 {@code
 * ai_brief.status=2}）。
 *
 * <p>携带已尝试的 provider 名序列与最后一次错误原因，便于日志与告警定位。 纯 JDK（继承 RuntimeException），领域层抛出/应用层捕获，不依赖框架。
 */
public class LlmException extends RuntimeException {

    private final List<String> attemptedProviders;

    public LlmException(String message, List<String> attemptedProviders, Throwable cause) {
        super(message, cause);
        this.attemptedProviders =
                attemptedProviders == null ? List.of() : new ArrayList<>(attemptedProviders);
    }

    /** 已尝试的 provider 名序列（含失败原因标注，顺序同 fallback 链）。 */
    public List<String> attemptedProviders() {
        return List.copyOf(attemptedProviders);
    }
}
