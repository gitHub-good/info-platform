package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 数据源域种子（{@code datasource.{SOURCE_CODE}} 7 键，T36；ADR-0032 起种子值全部取 {@link DataSourceDefaults}
 * 代码内置缺省，application.yml {@code adapter:} 段已删；ADR-0033 增降级链字段）。
 *
 * <p>首启 seed-if-absent 导入（DB 已有键不动，页面改过即权威）：超时/重试/缓存 TTL/params（URL/字段/referer/条数/备选源开关）取 {@link
 * DataSourceDefaults}；mode 取 {@link DataSourceDefaults#DEFAULT_MODE}（原 yml {@code
 * adapter.mock.enabled=true} 的语义平移）；enabled 恒 true；多 provider 源（QUOTE/VALUATION）种入默认降级链 {@code
 * ["eastmoney","tencent"]}（ADR-0033）。
 */
@Component
public class DataSourceRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public DataSourceRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        for (SourceCode code : SourceCode.values()) {
            seeds.add(seedOf(code));
        }
        return seeds;
    }

    private RuntimeConfigSeed seedOf(SourceCode code) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("enabled", true);
        doc.put("mode", DataSourceDefaults.DEFAULT_MODE.name());
        doc.put("timeoutMillis", DataSourceDefaults.timeoutMillis(code));
        doc.put("retries", DataSourceDefaults.RETRIES_NONE);
        doc.put("cacheTtlSeconds", DataSourceDefaults.cacheTtlSeconds(code));
        doc.put("failureCacheTtlSeconds", DataSourceDefaults.failureCacheTtlSeconds(code));
        doc.put("params", DataSourceDefaults.params(code));
        // ADR-0033 降级链：多 provider 源种入默认链（单 provider 源无备选，不种该字段）
        if (DataSourceDefaults.fallbackChain(code).size() > 1) {
            doc.put(
                    "fallbackChain",
                    DataSourceDefaults.fallbackChain(code).stream()
                            .map(SourceProvider::code)
                            .toList());
        }
        return new RuntimeConfigSeed(
                ConfigCenter.KEY_DATASOURCE_PREFIX + code.name(),
                write(doc),
                "数据源 " + code.name() + "（开关/模式/超时/重试/缓存 TTL/失败负缓存 TTL/降级链/外呼参数）");
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据源配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
