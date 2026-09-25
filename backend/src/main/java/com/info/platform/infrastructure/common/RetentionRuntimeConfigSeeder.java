package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.domain.retention.RetentionLogTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 留痕窗口种子（{@code retention.global}，T72，M10 技术方案增补 §4.1；T113 扩 newsItemDays）：五字段默认窗口取 {@link
 * RetentionLogTable} 枚举 defaultDays（30/14/90/90/180，代码内置默认，ADR-0032 同系列——无 yml 遗留可平移）。
 * seed-if-absent 由配置中心统一执行（DB 已有键不动，DB 为权威——存量四字段键缺 newsItemDays 时执行侧字段级回退 180，保存一次即补齐五字段）。
 */
@Component
public class RetentionRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public RetentionRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        Map<String, Object> doc = new LinkedHashMap<>();
        for (RetentionLogTable table : RetentionLogTable.values()) {
            doc.put(table.jsonField(), table.defaultDays());
        }
        return List.of(
                new RuntimeConfigSeed(
                        "retention.global",
                        write(doc),
                        "留痕数据保留窗口（五张表各自保留天数：任务日志/数据源事件/LLM 调用/阅读行为/资讯条目；"
                                + "下限 7/2/35/35/30 天，改大=多留，保存即热生效——下一轮清理按新窗口）"));
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("留痕窗口种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
