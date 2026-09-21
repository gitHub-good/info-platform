package com.info.platform.infrastructure.ai;

import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;

/**
 * LLM provider adapter 端口（基础设施层，每厂商一个实现）。
 *
 * <p>对齐技术方案 §4.4 + Spike-2 §11.1：每厂商一个 adapter（DeepSeek/GLM），共享 OpenAI 兼容请求/响应结构， 差异仅在
 * base-url/model/api-key。{@link AbstractOpenAiCompatProvider} 封装公共 RestClient 调用与响应解析， 子类只声明身份。
 *
 * <p>{@link #name} 为配置键（对齐 {@code llm.providers[].name}），供 {@code LlmGatewayImpl} 按 fallback 链查找
 * adapter。
 */
public interface LlmProviderAdapter {

    /** 本 adapter 对应的配置键，如 {@code "deepseek"} / {@code "glm"}。 */
    String name();

    /**
     * 调用本厂商 LLM。
     *
     * @throws org.springframework.web.client.RestClientResponseException 4xx/5xx（限频 429 等）
     * @throws org.springframework.web.client.ResourceAccessException 超时/连接错误
     * @throws IllegalStateException 本 provider 未配置（inert）
     */
    LlmResponse chat(LlmRequest request);
}
