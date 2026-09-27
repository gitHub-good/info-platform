package com.info.platform.infrastructure.valuation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.application.valuation.IncrementalReevalConfig;
import com.info.platform.application.valuation.IncrementalReevalConfigValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 增量重评配置种子（{@code incremental.reeval}，M22 方案 §3.5-1）：5 参数取 {@link
 * IncrementalReevalConfig#defaults()} 单一事实源（gap 0.5 / 联动间隔 10min / 扫描窗 24h / 缓冲 20s / 阈值
 * HIGH）。seed-if-absent 由配置中心统一执行（DB 已有键 不动，DB 为权威）。MarketTopRuntimeConfigSeeder 同款。
 */
@Component
public class IncrementalReevalRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public IncrementalReevalRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        IncrementalReevalConfig defaults = IncrementalReevalConfig.defaults();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("minScoreGap", defaults.minScoreGap());
        doc.put("linkMinIntervalMinutes", defaults.linkMinIntervalMinutes());
        doc.put("scanWindowHours", defaults.scanWindowHours());
        doc.put("eventBufferSeconds", defaults.eventBufferSeconds());
        doc.put("minImportance", defaults.minImportance().name());
        return List.of(
                new RuntimeConfigSeed(
                        IncrementalReevalConfigValidator.CONFIG_KEY,
                        write(doc),
                        "事件驱动增量重评配置（挤入挤出双向同阈迟滞 / 联动最小间隔防抖 / 事件扫描补跑窗与落库缓冲 / 触发阈值重要度"
                                + "——保存即热生效，下一轮 tick 按新参数，M22 方案 §3.5-1）"));
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("增量重评配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
