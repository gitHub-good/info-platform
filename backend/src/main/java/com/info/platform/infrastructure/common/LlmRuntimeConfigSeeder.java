package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.infrastructure.ai.LlmConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * LLM 域种子（{@code llm.global} + {@code llm.provider.*}，T34）。
 *
 * <p>首启从 {@link LlmConfig}（yml 绑定值）导入默认值；DB 已有键不动（页面改过即权威）。 <b>api-key 不种子</b>：环境变量
 * 为一等来源（部署零改动），页面写入密文后 DB 优先（ADR-0018）——种子只含 model/enabled/isDefault/fallback/单价/baseUrl。
 */
@Component
public class LlmRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper;

    public LlmRuntimeConfigSeeder(LlmConfig llmConfig, ObjectMapper objectMapper) {
        this.llmConfig = llmConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        seeds.add(globalSeed());
        for (LlmConfig.Provider provider : llmConfig.getProviders()) {
            seeds.add(providerSeed(provider));
        }
        return seeds;
    }

    private RuntimeConfigSeed globalSeed() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("timeoutSeconds", llmConfig.getTimeoutSeconds());
        doc.put("retry", llmConfig.getRetry());
        doc.put("dailyTokenBudgetPerUser", llmConfig.getDailyTokenBudgetPerUser());
        doc.put("budgetWarnRatio", llmConfig.getBudgetWarnRatio());
        doc.put("cacheDefaultTtlSeconds", llmConfig.getCache().getDefaultTtlSeconds());
        doc.put("cacheTtlSeconds", llmConfig.getCache().getTtl());
        return new RuntimeConfigSeed(
                ConfigCenter.KEY_LLM_GLOBAL, write(doc), "LLM 全局参数（超时/重试/日预算/告警阈值/缓存 TTL）");
    }

    private RuntimeConfigSeed providerSeed(LlmConfig.Provider provider) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("model", provider.getModel());
        doc.put("enabled", provider.isEnabled());
        doc.put("isDefault", provider.isDefault());
        doc.put("fallback", provider.getFallback());
        doc.put("inputPricePerMillion", provider.getInputPricePerMillion());
        doc.put("outputPricePerMillion", provider.getOutputPricePerMillion());
        doc.put("baseUrl", provider.getBaseUrl());
        return new RuntimeConfigSeed(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + provider.getName(),
                write(doc),
                "LLM provider " + provider.getName() + "（api-key 走环境变量或页面录入）");
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LLM 配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
