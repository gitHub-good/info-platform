package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 聚合编排种子（{@code aggregation.global}，T34）：详情页聚合总超时，yml 值首启导入。 */
@Component
public class AggregationRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    @Value("${aggregation.detail-timeout-millis:2000}")
    private long detailTimeoutMillis;

    public AggregationRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("detailTimeoutMillis", detailTimeoutMillis);
        return List.of(
                new RuntimeConfigSeed("aggregation.global", write(doc), "聚合编排总超时（详情页各源弹性超时的总预算）"));
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("聚合配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
