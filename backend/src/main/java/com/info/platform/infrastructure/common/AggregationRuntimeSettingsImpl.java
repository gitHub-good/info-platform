package com.info.platform.infrastructure.common;

import com.info.platform.application.aggregation.AggregationRuntimeSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link AggregationRuntimeSettings} 实现（T36）：每请求读 {@code aggregation.global} 快照（LIVE 级热生效），
 * 键缺失/解析异常回落 yml 绑定值（与 {@code AggregationRuntimeConfigSeeder} 种子来源同值）。
 */
@Component
public class AggregationRuntimeSettingsImpl implements AggregationRuntimeSettings {

    private static final String KEY = "aggregation.global";

    private static final String FIELD = "detailTimeoutMillis";

    private final ConfigCenter configCenter;

    @Value("${aggregation.detail-timeout-millis:2000}")
    private long ymlFallback;

    @Autowired
    public AggregationRuntimeSettingsImpl(ConfigCenter configCenter) {
        this(configCenter, 2000L);
    }

    /** 测试构造：显式 yml 回落值（生产走 @Value 字段注入）。 */
    AggregationRuntimeSettingsImpl(ConfigCenter configCenter, long ymlFallback) {
        this.configCenter = configCenter;
        this.ymlFallback = ymlFallback;
    }

    @Override
    public long detailTimeoutMillis() {
        return configCenter
                .document(KEY)
                .map(doc -> doc.path(FIELD).asLong(0))
                .filter(value -> value > 0)
                .orElse(ymlFallback);
    }
}
