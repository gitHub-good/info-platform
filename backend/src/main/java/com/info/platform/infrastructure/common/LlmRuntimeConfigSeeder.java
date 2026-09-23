package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.infrastructure.ai.LlmDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * LLM 域种子（{@code llm.global} + {@code llm.provider.*}，T34；种子来源自 ADR-0020 起为 {@link LlmDefaults}
 * 代码内置缺省）。
 *
 * <p>首启从内置缺省导入；DB 已有键不动（页面改过即权威）。 <b>api-key 不种子</b>：环境变量 为一等来源（部署零改动），页面写入密文后 DB
 * 优先（ADR-0018）——种子只含 model/enabled/isDefault/fallback/单价/baseUrl。
 */
@Component
public class LlmRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public LlmRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        seeds.add(globalSeed());
        for (LlmDefaults.Provider provider : LlmDefaults.providers()) {
            seeds.add(providerSeed(provider));
        }
        return seeds;
    }

    private RuntimeConfigSeed globalSeed() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("timeoutSeconds", LlmDefaults.TIMEOUT_SECONDS);
        doc.put("retry", LlmDefaults.RETRY);
        doc.put("dailyTokenBudgetPerUser", LlmDefaults.DAILY_TOKEN_BUDGET_PER_USER);
        doc.put("budgetWarnRatio", LlmDefaults.BUDGET_WARN_RATIO);
        doc.put("cacheDefaultTtlSeconds", LlmDefaults.CACHE_DEFAULT_TTL_SECONDS);
        doc.put("cacheTtlSeconds", LlmDefaults.CACHE_TTL_SECONDS);
        return new RuntimeConfigSeed(
                ConfigCenter.KEY_LLM_GLOBAL, write(doc), "LLM 全局参数（超时/重试/日预算/告警阈值/缓存 TTL）");
    }

    private RuntimeConfigSeed providerSeed(LlmDefaults.Provider provider) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("model", provider.model());
        doc.put("enabled", provider.enabled());
        doc.put("isDefault", provider.isDefault());
        doc.put("fallback", provider.fallback());
        doc.put("inputPricePerMillion", provider.inputPricePerMillion());
        doc.put("outputPricePerMillion", provider.outputPricePerMillion());
        doc.put("baseUrl", provider.baseUrl());
        return new RuntimeConfigSeed(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + provider.name(),
                write(doc),
                "LLM provider " + provider.name() + "（api-key 走环境变量或页面录入）");
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LLM 配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
