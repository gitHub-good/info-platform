package com.info.platform.infrastructure.markettop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.application.markettop.MarketTopConfig;
import com.info.platform.application.markettop.MarketTopConfigValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 榜单配置种子（{@code market.top}，M21 方案 §4.7.3）：5 参数取 {@link MarketTopConfig#defaults()} 单一事实源（池 300 /
 * 深析 40 / capRatio 0.30 / estimate 100000μ¥ / 成员覆盖率阈值 0.80）。seed-if-absent 由配置中心统一执行（DB 已有键不动，DB 为
 * 权威）。ScoreWeightRuntimeConfigSeeder 同款。
 */
@Component
public class MarketTopRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public MarketTopRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        MarketTopConfig defaults = MarketTopConfig.defaults();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("poolSize", defaults.poolSize());
        doc.put("deepDiveLimit", defaults.deepDiveLimit());
        doc.put("deepDiveCostCapRatio", defaults.deepDiveCostCapRatio());
        doc.put("diveCostEstimateMicros", defaults.diveCostEstimateMicros());
        doc.put("memberCoverageFloor", defaults.memberCoverageFloor());
        return List.of(
                new RuntimeConfigSeed(
                        MarketTopConfigValidator.CONFIG_KEY,
                        write(doc),
                        "全市场榜单漏斗配置（粗筛池 100~800 / LLM 深析候选 30~50 硬校验 / scene-10 成本护栏占比与单次预估"
                                + " / 行业成员覆盖率回填阈值；保存即热生效——下一轮 18:00 榜单按新参数，M21 方案 §4.7.3）"));
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("榜单配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
