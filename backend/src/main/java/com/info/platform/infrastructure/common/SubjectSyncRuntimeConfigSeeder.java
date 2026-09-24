package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.aggregation.SubjectSyncConfigValidator;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 标的池同步域种子（{@code subject.sync} 单键，ADR-0032；ADR-0033 增降级链字段）：A 股列表桶备选源自 yml {@code
 * subject.sync.a-share-source} 迁入 runtime_config 热化后，再升级为降级链模型。
 *
 * <p>种子值：{@code fallbackChain} 取 {@link DataSourceDefaults#aShareListFallbackChain()}（ {@code
 * ["eastmoney","sina"]}，写路径统一口径）；{@code aShareSource} 取 {@link
 * DataSourceDefaults#A_SHARE_LIST_SOURCE}（旧键兼容保留，读取侧缺链时折算）。键空间与校验见 {@link
 * SubjectSyncConfigValidator}。 分页参数（page-size/page-interval-millis/max-pages 等）仍走 yml（RESTART
 * 级，未在本批热化范围）。
 */
@Component
public class SubjectSyncRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public SubjectSyncRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        Map<String, Object> doc =
                Map.of(
                        "fallbackChain",
                        DataSourceDefaults.aShareListFallbackChain(),
                        "aShareSource",
                        DataSourceDefaults.A_SHARE_LIST_SOURCE);
        return List.of(
                new RuntimeConfigSeed(
                        SubjectSyncConfigValidator.KEY,
                        write(doc),
                        "标的池同步取数参数（A 股桶列表源降级链：东财失败自动降级新浪整桶重拉；旧键 aShareSource 兼容保留）"));
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("标的池同步配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
