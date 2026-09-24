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
 * 标的池同步域种子（{@code subject.sync} 单键，ADR-0032）：A 股列表桶备选源开关自 yml {@code subject.sync.a-share-source}
 * 迁入 runtime_config 热化（原 @Value 启动期绑定，改页面保存即生效）。
 *
 * <p>种子值取 {@link DataSourceDefaults#A_SHARE_LIST_SOURCE}（"auto"，值不变只换存放地）；键空间与校验见 {@link
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
        return List.of(
                new RuntimeConfigSeed(
                        SubjectSyncConfigValidator.KEY,
                        write(Map.of("aShareSource", DataSourceDefaults.A_SHARE_LIST_SOURCE)),
                        "标的池同步取数参数（A 股桶列表源选择：auto=东财失败自动降级新浪 / eastmoney / sina 强制单源）"));
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("标的池同步配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
